(ns sysrev.source.interface
  (:require [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction *conn*
              clear-project-cache]]
            [sysrev.db.articles :as articles]
            [sysrev.source.core :as s]
            [sysrev.shared.util :as su :refer [in?]]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.biosource.importance :as importance]
            [clojure.string :as str])
  (:import java.util.UUID))

(defn- add-articles
  "Implements adding article entries to db from sequence of article maps.

  Returns sequence of maps {article-id -> article} where article is
  the article map (prior to prepare-article) used as source to create
  the db entry with article-id field added."
  [project-id articles & [prepare-article]]
  (let [prepare-article-full
        (fn [article]
          (-> article (dissoc :locations) (assoc :enabled false)
              (#(if (nil? prepare-article) % (prepare-article %)))
              (update :primary-title
                      #(if (empty? %)
                         (format "[No Title Found] %s"
                                 (-> (UUID/randomUUID) str (str/split #"-") first))
                         %))))
        article-ids (articles/add-articles
                     (->> articles (mapv prepare-article-full))
                     project-id *conn*)]
    (map (fn [id article] {id (merge article {:article-id id})})
         article-ids articles)))

;; TODO: this should be customizable by source type (keep this as default)
(defn- match-existing-articles
  "Implements checking list of article maps for matches against existing
  project articles."
  [project-id articles]
  (let [ ;; check for matches of PMID value
        public-ids (->> articles (map :public-id) (remove nil?) (mapv str))
        existing-articles
        (if (empty? public-ids)
          []
          (-> (select :article-id :public-id)
              (from :article)
              (where [:and
                      [:= :project-id project-id]
                      [:in :public-id public-ids]])
              (->> do-query vec)))
        existing-article-ids
        (->> existing-articles (mapv :article-id))
        existing-public-ids
        (->> existing-articles (mapv :public-id) (filterv not-empty))
        have-article? #(some->> % :public-id (in? existing-public-ids))]
    {:new-articles (->> articles (remove have-article?))
     :existing-article-ids existing-article-ids}))

(defn- import-articles-impl
  "Implements single-threaded import of articles from a new project-source.
  Returns true on success, false on failure. Catches all exceptions,
  indicating failure in return value."
  [project-id source-id
   {:keys [article-refs get-articles prepare-article on-article-added] :as impl}]
  (letfn [(import-group [articles]
            (with-transaction
              (let [{:keys [new-articles existing-article-ids]}
                    (match-existing-articles project-id articles)
                    new-article-ids (add-articles project-id new-articles prepare-article)
                    new-articles-map (apply merge new-article-ids)
                    new-locations
                    (->> (keys new-articles-map)
                         (map (fn [article-id]
                                (let [article (get new-articles-map article-id)]
                                  (->> (:locations article)
                                       (mapv #(assoc % :article-id article-id))))))
                         (apply concat)
                         vec)]
                (s/add-articles-to-source
                 (concat existing-article-ids (keys new-articles-map))
                 source-id)
                (when (not-empty new-locations)
                  (-> (sqlh/insert-into :article-location)
                      (values new-locations)
                      do-execute))
                (when on-article-added
                  (try
                    (doseq [article-id (->> new-article-ids (map #(-> % keys first)))]
                      (on-article-added (get new-articles-map article-id)))
                    (catch Throwable e
                      (log/warn "import-articles-impl: exception in on-article-added")
                      (throw e)))))))]
    (try
      (doseq [articles (->> (get-articles article-refs) (partition-all 10))]
        (try
          (import-group articles)
          (catch Throwable e
            (log/warn "import-articles-impl: error importing group -" (.getMessage e))
            (log/warn "attempting again with individual articles")
            (doseq [article articles]
              (try
                (import-group [article])
                (catch Throwable e1
                  (log/warn "import error for article -" (.getMessage e1))))))))
      true
      (catch Throwable e
        (log/warn "import-articles-impl:" (.getMessage e))
        (.printStackTrace e)
        false)
      (finally
        (clear-project-cache project-id)))))

(defn- import-source-articles
  "Implements multi-threaded import of articles from a new project-source.
  Returns true on success, false on failure. Catches all exceptions,
  indicating failure in return value."
  [project-id source-id
   {:keys [article-refs get-articles prepare-article on-article-added] :as impl}
   threads]
  (if (and (> threads 1) (nil? *conn*))
    (try
      (let [group-size (->> (quot (count article-refs) threads) (max 1))
            thread-groups (->> article-refs (partition-all group-size))
            thread-results
            (->> thread-groups
                 (mapv
                  (fn [thread-refs]
                    (future (import-articles-impl
                             project-id source-id
                             (-> impl (assoc :article-refs thread-refs))))))
                 (mapv deref))]
        (every? true? thread-results))
      (catch Throwable e
        (log/warn "Error in import-source-articles:" (.getMessage e))
        false))
    (import-articles-impl project-id source-id impl)))

(defn- after-source-import
  "Handles success or failure after an import attempt has finished."
  [project-id source-id success?]
  (with-transaction
    ;; update source metadata
    (if success?
      (s/alter-source-meta
       source-id #(assoc % :importing-articles? false))
      (s/fail-source-import source-id))
    ;; update the enabled flag for the articles
    (s/update-project-articles-enabled project-id))
  ;; start threads for updates from api.insilica.co
  (when success?
    (predict-api/schedule-predict-update project-id)
    (importance/schedule-important-terms-update project-id))
  success?)

(defn import-source-impl
  "Top-level function for running import of a new source with
  articles. This should only be called directly from an import-source
  method implementation for a source type.

  get-article-refs is a function returning a sequence of
  values (article-refs) that can be used to obtain an article.

  get-articles is a function that accepts an article-refs sequence and
  returns a sequence of corresponding article values. The article
  values should be maps, and may contain extra custom fields (e.g. for
  use by on-article-added)

  prepare-article is an optional function that will be applied to
  transform article values for inserting to database.

  on-article-added is an optional function that will be called for
  each article value immediately after the article has been
  successfully added to database. on-article-added will receive the
  same article value that was obtained from get-articles, with
  an :article-id key added to reference the new article entry.

  use-future? is a boolean value (defaults to true) that controls
  whether this function will wrap the top-level import call in a
  future and return immediately. When false, this function will
  return after the import attempt has completed.

  threads is an integer controlling the number of threads that will be
  used to import articles in parallel."
  [project-id source-meta
   {:keys [get-article-refs get-articles prepare-article on-article-added] :as impl}
   {:keys [use-future? threads] :or {use-future? true threads 4}}]
  (let [blocking? (boolean (or (not use-future?) *conn*))
        source-id (s/create-source
                   project-id (assoc source-meta :importing-articles? true))
        do-import (fn []
                    (->> (try (import-source-articles
                               project-id source-id
                               (-> impl
                                   (assoc :article-refs (get-article-refs))
                                   (dissoc :get-article-refs))
                               threads)
                              (catch Throwable e
                                (log/warn "import-source-impl failed -" (.getMessage e))
                                (.printStackTrace e)
                                false))
                         (after-source-import project-id source-id)))]
    {:source-id source-id
     :import (if blocking? (do-import) (future (do-import)))}))

(defmulti import-source
  "Multimethod for import implementation per source type."
  (fn [stype project-id input options] stype))

(defmethod import-source :default [stype project-id input options]
  (throw (Exception. (format "import-source - invalid source type (%s)"
                             (pr-str stype)))))
