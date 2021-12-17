(ns sysrev.source.interface
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :refer [remove-vals]]
            [sysrev.db.core :as db :refer [*conn*]]
            [sysrev.db.queries :as q]
            [sysrev.article.core :as article]
            [sysrev.datasource.core :as ds]
            [sysrev.notification.interface :refer [create-notification]]
            [sysrev.source.core :as source]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.slack :refer [log-slack-custom]]
            [sysrev.stacktrace :as strace]
            [sysrev.util :as util :refer [in? parse-integer pp-str]])
  (:import java.util.UUID))

(defn- add-articles-data [{:keys [article-type article-subtype] :as types} articles]
  (doall (for [article articles]
           (-> (ds/make-article-data types article)
               (ds/save-article-data)))))

(defn- add-articles
  "Implements adding article entries to db from sequence of article maps.

  Returns sequence of maps {article-id -> article} where article is
  the article map (prior to prepare-article) used as source to create
  the db entry with article-id field added."
  [project-id articles {:keys [article-type article-subtype] :as types} & [prepare-article]]
  (let [prepare-article-full
        (fn [article]
          (-> article (dissoc :locations) (assoc :enabled false)
              (#(if (nil? prepare-article) % (prepare-article %)))
              (update :primary-title
                      #(if (empty? %)
                         (format "[No Title Found] %s"
                                 (-> (UUID/randomUUID) str (str/split #"-") first))
                         %))))
        ;; do initial processing on `articles` to create db entries
        articles-prepared (mapv prepare-article-full articles)
        ;; create `article-data` entries
        article-data-ids (add-articles-data types articles-prepared)
        ;; add `article-data-id` value and
        ;; strip most fields before creating `article` table entries
        articles-basic (mapv (fn [article article-data-id]
                               (-> (merge article {:article-data-id article-data-id})
                                   (dissoc :raw :abstract :date :urls :document-ids :keywords
                                           :year :nct-arm-name :notes :nct-arm-desc
                                           :remote-database-name :authors :secondary-title
                                           :work-type :text-search
                                           :primary-title :public-id :external-id)))
                             articles-prepared article-data-ids)
        ;; create `article` entries
        article-ids (article/add-articles articles-basic project-id *conn*)]
    ;; return input `articles` with `article-id` values attached
    (map (fn [id article] {id (merge article {:article-id id})})
         article-ids articles)))

(defn- match-existing-articles
  "Implements checking list of article maps for matches against existing
  project articles."
  [project-id articles {:keys [article-type article-subtype] :as types}]
  (let [datasource-name (ds/datasource-name-for-type types)
        ;; check for matches of PMID value
        external-ids (when datasource-name
                       (if (= datasource-name "pubmed")
                         (->> articles (map :external-id) (map parse-integer) (remove nil?) (map str))
                         (->> articles (map :external-id) (remove nil?))))
        existing (when (seq external-ids)
                   (q/find [:article :a] {:a.project-id project-id
                                          :ad.external-id (map db/to-jsonb external-ids)
                                          :ad.datasource-name datasource-name}
                           [:a.article-id :ad.external-id]
                           :join [[:article-data :ad] :a.article-data-id]))
        existing-article-ids (map :article-id existing)
        existing-external-ids (map :external-id existing)
        have-article? #(some->> % :external-id str (in? existing-external-ids))]
    {:new-articles (remove have-article? articles)
     :existing-article-ids existing-article-ids}))

#_:clj-kondo/ignore
;; TODO: store article-location data in datasource
(defn- import-articles-impl
  "Implements single-threaded import of articles from a new project-source.
  Returns true on success, false on failure. Catches all exceptions,
  indicating failure in return value."
  [request project-id source-id
   {:keys [article-refs get-articles prepare-article on-article-added types] :as _impl}
   & [{:keys [user-id] :as _opts}]]
  (assert (map? request))
  (letfn [(import-group [articles]
            (db/with-long-transaction [_ (get-in request [:web-server :postgres])]
              (let [{:keys [new-articles existing-article-ids]}
                    (match-existing-articles project-id articles types)
                    new-article-ids (add-articles project-id new-articles types prepare-article)
                    new-articles-map (apply merge new-article-ids)
                    new-locations
                    (->> (keys new-articles-map)
                         (map (fn [article-id]
                                (let [article (get new-articles-map article-id)]
                                  (->> (:locations article)
                                       (mapv #(assoc % :article-id article-id))))))
                         (apply concat)
                         vec)
                    user-name (when user-id
                                (q/find-one :web-user {:user-id user-id} :username))
                    project-name (q/find-one :project {:project-id project-id} :name)
                    notification (remove-vals nil? {:adding-user-id user-id
                                                    :adding-user-name user-name
                                                    :project-id project-id
                                                    :project-name project-name
                                                    :type :project-has-new-article})]
                (source/add-articles-to-source
                 (concat existing-article-ids (keys new-articles-map))
                 source-id)
                (q/create :article-location new-locations)
                (doseq [article-id (->> new-article-ids (map #(-> % keys first)))]
                  (->> (q/find-one [:article :a]
                                   {:a.article-id article-id}
                                   :ad.title
                                   :join [[:article-data :ad] [:= :a.article-data-id :ad.article-data-id]])
                   (assoc notification :article-id article-id :article-data-title)
                   create-notification)
                  (when on-article-added
                    (try
                      (on-article-added (get new-articles-map article-id))
                      (catch Exception e
                        (log/error "import-articles-impl: exception in on-article-added")
                        (log/error (with-out-str (strace/print-cause-trace-custom e)))
                        (throw e))))))))]
    (try (doseq [articles (partition-all 10 (get-articles article-refs))]
           (try (import-group articles)
                (catch Exception e
                  (log/error "import-articles-impl: error importing group -" (.getMessage e))
                  (log/error (with-out-str (strace/print-cause-trace-custom e)))
                  (log/warn "attempting again with individual articles")
                  (doseq [article articles]
                    (try (import-group [article])
                         (catch Exception e1
                           (log/error "import error for article -" (.getMessage e1))))))))
         true
         (catch Exception e
           (log/error "import-articles-impl:" (.getMessage e))
           (log/error (with-out-str (strace/print-cause-trace-custom e)))
           false)
         (finally (db/clear-project-cache project-id)))))

(defn import-source-articles
  "Implements multi-threaded import of articles from a new project-source.
  Returns true on success, false on failure. Catches all exceptions,
  indicating failure in return value."
  [request project-id source-id
   {:keys [article-refs get-articles prepare-article on-article-added types] :as impl}
   {:keys [threads] :as opts}]
  (assert (map? request))
  (db/with-long-transaction [_ (get-in request [:web-server :postgres])]
    (if (> threads 1)
      (try (let [group-size (->> (quot (count article-refs) threads) (max 1))
                 thread-groups (->> article-refs (partition-all group-size))
                 threads (doall (for [thread-refs thread-groups]
                                  (future (import-articles-impl
                                           request project-id source-id
                                           (assoc impl :article-refs thread-refs)
                                           opts))))]
             (every? true? (mapv deref threads)))
           (catch Exception e
             (log/error "Error in import-source-articles:" (.getMessage e))
             false))
      (import-articles-impl request project-id source-id impl opts))))

(defn after-source-import
  "Handles success or failure after an import attempt has finished."
  [request project-id source-id success?]
  (db/with-long-transaction [_ (get-in request [:web-server :postgres])]
    ;; update source metadata
    (if success?
      (source/alter-source-meta source-id #(assoc % :importing-articles? false))
      (source/fail-source-import source-id))
    ;; log errors
    (try (let [source (q/find-one :project-source {:source-id source-id})]
           (cond (not success?)
                 (log-slack-custom ["*Article source import failed*"
                                    (format "*Source*:\n```%s```" (pp-str {:source source}))]
                                   "Article source import failed")
                 (and success? (zero? (q/find-count :article-source {:source-id source-id})))
                 (log-slack-custom ["*Article source import - 0 articles loaded*"
                                    (format "*Source*:\n```%s```" (pp-str {:source source}))]
                                   "Article source import - 0 articles loaded")))
         (catch Throwable _
           (log/error "after-source-import - logging error to slack failed")))
    ;; update the enabled flag for the articles
    (source/update-project-articles-enabled project-id))
  ;; start threads for updates from api.insilica.co
  (when success?
    (predict-api/schedule-predict-update project-id))
  success?)

(defn import-source-impl
  "Top-level function for running import of a new source with
  articles. This should only be called directly from an import-source
  method implementation for a source type.

  `types` is a required map with keys `:article-type` and
  `:article-subtype`.

  `get-article-refs` is a function returning a sequence of
  values (`article-refs`) that can be used to obtain an article.

  `get-articles` is a function that accepts an `article-refs` sequence
  and returns a sequence of corresponding article values. The article
  values should be maps, and may contain extra custom fields (e.g. for
  use by `on-article-added`). `:primary-title` is a required value;
  `:external-id` should be provided for article types that reference
  an external datasource.

  Note: The `:external-id`s will only be processed, when the
  `:article-type` and `:article-subtype` are defined in
  `sysrev.datasource.core/datasource-name-for-type`,
  otherwise they will be ignored

  `prepare-article` is an optional function that will be applied to
  transform article values when inserting to database.

  `on-article-added` is an optional function that will be called for
  each article value immediately after the article has been
  successfully added to database. `on-article-added` will receive the
  same article value that was obtained from `get-articles`, with
  an `:article-id` key added to reference the new article entry.

  `use-future?` is a boolean value (defaults to true) that controls
  whether this function will wrap the top-level import call in a
  future and return immediately. When false, this function will
  return after the import attempt has completed.

  `threads` is an integer controlling the number of threads that will be
  used to import articles in parallel.

  `filename` and `file` are optional arguments providing a file the
  import data is coming from; if given, the file will be stored to s3
  and referenced in the source meta map."
  [request project-id source-meta
   {:keys [get-article-refs get-articles prepare-article on-article-added types] :as impl}
   {:keys [use-future? user-id threads] :or {use-future? true threads 4}}
   & {:keys [filename file]}]
  (assert (map? request))
  (let [source-id (source/create-source project-id (assoc source-meta :importing-articles? true))
        do-import
        (fn []
          (db/with-long-transaction [_ (get-in request [:web-server :postgres])]
            (->> (try
                   (when (and filename file)
                     (source/save-import-file source-id filename file))
                   (import-source-articles
                    request project-id source-id
                    (-> (assoc impl :article-refs (get-article-refs))
                        (dissoc :get-article-refs))
                    {:threads threads :user-id user-id})
                   (catch Exception e
                     (log/error "import-source-impl failed -" (.getMessage e))
                     (.printStackTrace e)
                     false))
                 (after-source-import request project-id source-id))))]
    {:source-id source-id
     :import (if use-future? (future (do-import)) (do-import))}))

(defmulti import-source
  "Multimethod for import implementation per source type."
  (fn [_request stype _project-id _input & [_options]] stype))

(defmethod import-source :default [_request stype _project-id _input & [_options]]
  (throw (Exception. (format "import-source - invalid source type (%s)"
                             (pr-str stype)))))
