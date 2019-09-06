(ns sysrev.article.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.db.query-types :as qt]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.util :as sutil :refer
             [in? map-values index-by ensure-pred parse-integer]]))

(defn merge-article-data-content [article]
  (merge (dissoc article :content)
         (:content article)))

(defn-spec article-to-sql map?
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article ::sa/article-partial, & [conn] (s/? any?)]
  (try (-> article
           (cond-> (:authors article)
             (update :authors #(db/to-sql-array "text" % conn)))
           (cond-> (:keywords article)
             (update :keywords #(db/to-sql-array "text" % conn)))
           (cond-> (:urls article)
             (update :urls #(db/to-sql-array "text" % conn)))
           (cond-> (:document-ids article)
             (update :document-ids #(db/to-sql-array "text" % conn))))
       (catch Throwable e
         (log/warn "article-to-sql: error converting article")
         (log/warn "article =" (pr-str article))
         (throw e))))

(defn-spec add-article int?
  [article ::sa/article-partial, project-id int?, & [conn] (s/? any?)]
  (q/create :article (-> (article-to-sql article conn)
                         (assoc :project-id project-id))
            :returning :article-id))

(defn add-articles [articles project-id & [conn]]
  (vec (when (seq articles)
         (q/create :article (mapv #(-> (article-to-sql % conn)
                                       (assoc :project-id project-id))
                                  articles)
                   :returning :article-id))))

(defn-spec set-user-article-note map?
  [article-id int?, user-id int?, note-name string?, content (s/nilable string?)]
  (let [{:keys [project-id project-note-id] :as pnote}
        (q/find-one [:article :a] {:a.article-id article-id :pn.name note-name}
                    :pn.*, :join [[:project:p :a.project-id]
                                  [:project-note:pn :p.project-id]])
        anote (q/find-one [:article :a] {:a.article-id article-id
                                         :an.user-id user-id
                                         :pn.name note-name}
                          :an.*, :join [[:article-note:an :a.article-id]
                                        [:project-note:pn :an.project-note-id]])]
    (assert pnote "note type not defined in project")
    (assert project-id "project-id not found")
    (db/with-clear-project-cache project-id
      (let [fields {:article-id article-id
                    :user-id user-id
                    :project-note-id project-note-id
                    :content content
                    :updated-time (db/sql-now)}]
        (if (nil? anote)
          (q/create :article-note fields, :returning :*)
          (first (q/modify :article-note {:article-id article-id
                                          :user-id user-id
                                          :project-note-id project-note-id}
                           fields, :returning :*)))))))

(defn-spec article-user-notes-map (s/map-of int? any?)
  [project-id int?, article-id int?]
  (db/with-project-cache project-id [:article article-id :notes :user-notes-map]
    (->> (q/find [:article :a] {:a.article-id article-id} [:an.* :pn.name]
                 :join [[:article-note:an :a.article-id]
                        [:project-note:pn :an.project-note-id]])
         (group-by :user-id)
         (map-values #(->> % (index-by :name) (map-values :content))))))

(defn-spec remove-article-flag int?
  [article-id int?, flag-name string?]
  (q/delete :article-flag {:article-id article-id :flag-name flag-name}))

(defn-spec set-article-flag map?
  [article-id int?, flag-name string?, disable? boolean?
   & [meta] (s/? (s/cat :meta map?))]
  (db/with-transaction
    (remove-article-flag article-id flag-name)
    (q/create :article-flag {:article-id article-id
                             :flag-name flag-name
                             :disable disable?
                             :meta (some-> meta db/to-jsonb)}
              :returning :*)))

(defn article-locations-map [article-id]
  (q/find :article-location {:article-id article-id}
          [:source :external-id], :group-by :source))

(defn article-flags-map [article-id]
  (q/find [:article :a] {:a.article-id article-id}
          (db/table-fields :aflag [:disable :date-created :meta])
          :join [:article-flag:aflag :a.article-id]
          :index-by :flag-name))

(defn article-sources-list [article-id]
  (q/find [:article :a] {:a.article-id article-id} :as.source-id
          :join [:article-source:as :a.article-id]))

(defn article-score
  [article-id & {:keys [predict-run-id] :as opts}]
  (db/with-transaction
    (let [predict-run-id (or predict-run-id (q/article-latest-predict-run-id article-id))]
      (:score (q/find-one [:article :a] {:a.article-id article-id} :*
                          :prepare #(q/with-article-predict-score % predict-run-id))))))

;; TODO: replace with generic interface for querying db entities with added values
(defn get-article
  "Queries for article data by id, with data from other tables included.

  `items` is an optional sequence of keywords configuring which values
  to include, defaulting to all possible values.

  `predict-run-id` allows for specifying a non-default prediction run to
  use for the prediction score."
  [article-id & {:keys [items predict-run-id]
                 :or {items [:locations :score :flags :sources]}
                 :as opts}]
  (assert (->> items (every? #(in? [:locations :score :flags :sources] %))))
  (let [article-data (q/find-one [:article :a] {:article-id article-id} [:a.* :ad.content]
                                 :join [:article-data:ad :a.article-data-id])
        article (merge (select-keys article-data [:article-id :project-id])
                       (:content article-data))
        get-item (fn [item-key f] (when (in? items item-key)
                                    (constantly {item-key (f)})))
        item-values
        (when (not-empty article)
          ;; For each key in `items` run function to get corresponding value,
          ;; then merge all together into a single map.
          ;; (load items in parallel using `pcalls`)
          (->> [(get-item :locations #(article-locations-map article-id))
                (get-item :score #(or (article-score article-id :predict-run-id predict-run-id)
                                      0.0))
                (get-item :flags #(article-flags-map article-id))
                (get-item :sources #(article-sources-list article-id))]
               (remove nil?) (apply pcalls) doall (apply merge {})))]
    (some-> (not-empty article) (merge item-values))))

;; TODO: move this to cljc, client project duplicates this function
(defn article-location-urls [locations]
  (->> [:pubmed :doi :pii :nct]
       (map (fn [source]
              (map #(let [ext-id (:external-id %)]
                      (case (keyword source)
                        :pubmed  (str "https://www.ncbi.nlm.nih.gov/pubmed/?term=" ext-id)
                        :doi     (str "https://dx.doi.org/" ext-id)
                        :pmc     (str "https://www.ncbi.nlm.nih.gov/pmc/articles/" ext-id "/")
                        :nct     (str "https://clinicaltrials.gov/ct2/show/" ext-id)
                        nil))
                   (get locations (name source)))))
       (apply concat)
       (filter identity)))

(defn project-prediction-scores
  "Given a project-id, return the prediction scores for all articles"
  [project-id &
   {:keys [include-disabled? predict-run-id]
    :or {include-disabled? false
         predict-run-id (first (q/find :predict-run {:project-id project-id} :predict-run-id
                                       :order-by [:create-time :desc] :limit 1))}}]
  (q/find [:label-predicts :lp] (cond-> {:a.project-id project-id
                                         :lp.predict-run-id predict-run-id}
                                  (not include-disabled?) (merge {:a.enabled true}))
          [:a.article-id :lp.val]
          :join [:article:a :lp.article-id]))

(defn article-ids-to-uuids [article-ids]
  (->> (partition-all 500 article-ids)
       (mapv #(q/find :article {:article-id %} :article-uuid))
       (apply concat)))

;; TODO: get this PMCID value from somewhere other than raw xml
(defn article-pmcid [article-id]
  nil
  #_ (some->> (qt/get-article article-id :raw) (re-find #"PMC\d+")))

(defn modify-articles-by-id
  "Runs SQL update setting `values` on articles in `article-ids`."
  [article-ids values]
  (doseq [id-group (partition-all 100 article-ids)]
    (when (seq id-group)
      (q/modify :article {:article-id id-group} values))))
