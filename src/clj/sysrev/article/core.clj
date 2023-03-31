(ns sysrev.article.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [orchestra.core :refer [defn-spec]]
   [sysrev.datasource.api :as ds-api]
   [sysrev.db.core :as db]
   [sysrev.db.queries :as q]
   [sysrev.project.core :refer [project-overall-label-id]]
   [sysrev.shared.spec.article :as sa]
   [sysrev.shared.spec.core :as sc]
   [sysrev.util :as util :refer [in? index-by]]
   [medley.core :as medley]))

(defn-spec article-to-sql map?
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article ::sa/article-partial
   & [conn] (s/? any?)]
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

(def article-cols
  [:article-data-id :article-id :article-uuid :duplicate-of :enabled
   :last-user-assigned :last-user-review :parent-article-uuid :project-id])

(defn-spec add-articles (s/every ::sa/article-id)
  [articles (s/every ::sa/article-partial)
   project-id ::sc/sql-serial-id
   & [conn] (s/? any?)]
  (vec (q/create :article (mapv #(-> (select-keys % article-cols)
                                     (article-to-sql conn)
                                     (assoc :project-id project-id))
                                articles)
                 :returning :article-id)))

(defn-spec set-user-article-note map?
  [article-id ::sa/article-id
   user-id ::sc/user-id
   content (s/nilable string?)]
  (let [project-id (q/get-article article-id :project-id)
        anote (q/find-one [:article :a] {:a.article-id article-id :an.user-id user-id}
                          :an.*, :join [[:article-note :an] :a.article-id])]
    (when project-id
      (db/with-clear-project-cache project-id
        (let [fields {:article-id article-id
                      :user-id user-id
                      :content content
                      :updated-time :%now}]
          (if (nil? anote)
            (q/create :article-note fields, :returning :*)
            (first (q/modify :article-note {:article-id article-id :user-id user-id}
                             fields, :returning :*))))))))

(defn-spec article-user-notes-map (s/map-of int? string?)
  [article-id ::sa/article-id]
  (->> (q/find [:article :a] {:a.article-id article-id}
               :an.*, :join [[:article-note :an] :a.article-id])
       (index-by :user-id)
       (medley/map-vals :content)))

(defn-spec article-locations-map (s/nilable (s/every map?))
  [article-id ::sa/article-id]
  (q/find :article-location {:article-id article-id}
          [:source :external-id], :group-by :source))

(defn-spec article-flags-map (s/nilable (s/every map?))
  [article-id ::sa/article-id]
  (q/find [:article :a] {:a.article-id article-id}
          (db/table-fields :aflag [:disable :date-created :meta])
          :join [[:article-flag :aflag] :a.article-id]
          :index-by :flag-name))

(defn-spec article-sources-list (s/nilable (s/every ::sc/sql-serial-id))
  [article-id ::sa/article-id]
  (q/find [:article :a] {:a.article-id article-id} :as.source-id
          :join [[:article-source :as] :a.article-id]))

(s/def :sysrev.article.core.article-score/predict-run-id (s/nilable ::sc/sql-serial-id))

(defn-spec article-score ::sa/score
  [article-id ::sa/article-id
   & {:keys [predict-run-id]} (s/keys* :opt-un [:sysrev.article.core.article-score/predict-run-id])]
  (db/with-transaction
    (let [project-id (q/get-article article-id :project-id)
          predict-run-id (or predict-run-id (q/project-latest-predict-run-id project-id))]
      (first
       (q/find :label-predicts {:article-id article-id
                                :label-id (project-overall-label-id project-id)
                                :predict-run-id predict-run-id
                                :stage 1
                                :label-value "TRUE"}
                   :val)))))

(defn-spec article-predictions map?
  [article-id ::sa/article-id
   & {:keys [predict-run-id]} (s/keys* :opt-un [:sysrev.article.core.article-score/predict-run-id])]
  (->> (db/with-transaction
         (let [predict-run-id (or predict-run-id (-> (q/get-article article-id :project-id)
                                                     (q/project-latest-predict-run-id)))]
           (q/find [:article :a] {:a.article-id article-id
                                  :lp.predict-run-id predict-run-id
                                  :lp.stage 1}
                   [:lp.label-id :lp.label-value :lp.val]
                   :join [[[:label-predicts :lp] :a.article-id]])))
       (reduce (fn [acc {:keys [label-id label-value val]}]
                 (assoc-in acc [label-id label-value] val))
               {})))

(def get-article-items #{:flags :locations :score :sources})

(s/def :sysrev.article.core.get-article/items (s/every get-article-items))

(defn-spec get-article ::sa/article-partial
  "Queries for article data by id, with data from other tables included.

  `items` is an optional sequence of keywords configuring which values
  to include, defaulting to all possible values.

  `predict-run-id` allows for specifying a non-default prediction run to
  use for the prediction score."
  [article-id ::sa/article-id
   & {:keys [items predict-run-id]
      :or {items get-article-items}}
   (s/keys* :opt-un [:sysrev.article.core.get-article/items ::predict-run-id])]
  (let [article (ds-api/get-article-content article-id)
        get-item (fn [item-key f] (when (in? items item-key)
                                    {item-key (f)}))]
    (when (seq article)
      (merge
       article
       (get-item :flags #(article-flags-map article-id))
       (get-item :locations #(article-locations-map article-id))
       (get-item :score #(or (article-score article-id :predict-run-id predict-run-id)
                             0.0))
       (get-item :sources #(article-sources-list article-id))))))

;; TODO: move this to cljc, client project duplicates this function
(defn-spec article-location-urls (s/every string?)
  [locations ::sa/locations]
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

;; FIX: get this PMCID value from somewhere other than raw xml
(defn article-pmcid [_article-id]
  nil
  #_ (some->> (q/get-article _article-id :raw) (re-find #"PMC\d+")))

(defn-spec modify-articles-by-id nil?
  "Runs SQL update setting `values` on articles in `article-ids`."
  [article-ids (s/every ::sa/article-id)
   values map?]
  (doseq [id-group (partition-all 100 article-ids)]
    (when (seq id-group)
      (q/modify :article {:article-id id-group} values))))

(defn gpt-answers [sr-context {:keys [project-id uri] :as _article}]
  (when uri
    (let [docs (-> sr-context
                   (db/execute!
                    {:select :srvc-document/hash
                     :from :srvc-document
                     :join [:srvc-document-to-project
                            [:= :srvc-document/hash :srvc-document-to-project/hash]]
                     :where [:and
                             [:= :uri uri]
                             [:= :srvc-document-to-project/project-id project-id]]})
                   (->> (map :srvc-document/hash)))]
      (when (seq docs)
        (-> sr-context
            (db/execute!
             {:select [:srvc-label.data :srvc-label.hash :srvc-label-answer.answer]
              :from :srvc-label-answer
              :join [:srvc-label [:= :srvc-label.hash :srvc-label-answer.label]]
              :where [:and
                      [:= :reviewer "https://github.com/insilica/sfac/tree/master/gpt4-label"]
                      [:in :document docs]]
              :order-by [[:timestamp :desc]]})
            (->>
             (reduce
              (fn [m {:srvc-label/keys [data hash]
                      :srvc-label-answer/keys [answer]}]
                (update m (keyword (:id data)) #(or % answer)))
              {})))))))
