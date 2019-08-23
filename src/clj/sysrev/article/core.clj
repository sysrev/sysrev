(ns sysrev.article.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-project-cache clear-project-cache]]
            [sysrev.db.entity :as e]
            [sysrev.db.queries :as q]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.util :as u :refer [in? map-values index-by]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defn article-project-id
  "Get project-id value from article-id."
  [article-id]
  (-> (select :project-id)
      (from :article)
      (where [:= :article-id article-id])
      do-query first :project-id))

(defn-spec article-to-sql map?
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article ::sa/article-partial, & [conn] (s/? any?)]
  (try (-> article
           (update :authors #(db/to-sql-array "text" % conn))
           (update :keywords #(db/to-sql-array "text" % conn))
           (update :urls #(db/to-sql-array "text" % conn))
           (update :document-ids #(db/to-sql-array "text" % conn)))
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
        (-> (q/select-article-by-id article-id [:pn.*])
            (merge-join [:project :p] [:= :p.project-id :a.project-id])
            (q/with-project-note note-name)
            do-query first)
        anote (-> (q/select-article-by-id article-id [:an.*])
                  (q/with-article-note note-name user-id)
                  do-query first)]
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
  (with-project-cache project-id [:article article-id :notes :user-notes-map]
    (-> (q/select-article-by-id article-id [:an.* :pn.name])
        (q/with-article-note)
        (->> do-query
             (group-by :user-id)
             (map-values #(->> (index-by :name %)
                               (map-values :content)))))))

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
  (-> (select :al.source :al.external-id)
      (from [:article-location :al])
      (where [:= :al.article-id article-id])
      (->> do-query (group-by :source))))

(defn article-flags-map [article-id]
  (-> (q/select-article-by-id
       article-id (db/table-fields :aflag [:flag-name :disable :date-created :meta]))
      (q/join-article-flags)
      (->> do-query
           (index-by :flag-name)
           (map-values #(dissoc % :flag-name)))))

(defn article-sources-list [article-id]
  (-> (q/select-article-by-id article-id [:asrc.source-id])
      (q/join-article-source)
      (->> do-query (mapv :source-id))))

(defn article-score
  [article-id & {:keys [predict-run-id] :as opts}]
  (db/with-transaction
    (-> (q/select-article-by-id article-id [:a.article-id])
        (q/with-article-predict-score
          (or predict-run-id (q/article-latest-predict-run-id article-id)))
        do-query first :score)))

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
  (let [article (-> (q/query-article-by-id article-id [:a.*])
                    (dissoc :text-search))
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
  [project-id & {:keys [include-disabled? predict-run-id]
                 :or {include-disabled? false
                      predict-run-id (-> (select :predict-run-id)
                                         (from [:predict-run :pr])
                                         (where [:= :pr.project-id project-id])
                                         (order-by [:pr.create-time :desc])
                                         (limit 1)
                                         do-query first :predict-run-id)}}]
  (-> (select :lp.article-id :lp.val)
      (from [:label-predicts :lp])
      (join [:article :a] [:= :lp.article-id :a.article-id])
      (where [:and (when-not include-disabled? [:= :a.enabled true])
              [:= :a.project-id project-id]
              [:= :lp.predict-run-id predict-run-id]])
      do-query))

;; TODO: replace with a generic select-by-field-values function
(defn article-ids-to-uuids [article-ids]
  (->> (partition-all 500 article-ids)
       (mapv #(-> (select :article-uuid)
                  (from [:article :a])
                  (where [:in :article-id %])
                  (->> do-query (map :article-uuid))))
       (apply concat)))

(defn article-pmcid
  "Given an article id, return it's pmcid. Returns nil if it does not exist"
  [article-id]
  (-> (select :raw)
      (from :article)
      (where [:= :article-id article-id])
      do-query (some->> first :raw (re-find #"PMC\d+"))))

;; TODO: replace with generic function
(defn modify-articles-by-id
  "Runs SQL update setting `values` on articles in `article-ids`."
  [article-ids values]
  (doseq [id-group (partition-all 100 article-ids)]
    (when (seq id-group)
      (-> (sqlh/update :article)
          (sset values)
          (where [:in :article-id (vec id-group)])
          do-execute))))
