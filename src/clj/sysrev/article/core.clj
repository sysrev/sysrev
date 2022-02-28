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
   [sysrev.util :as util :refer [in? index-by map-values]]))

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

(defn-spec add-article ::sa/article-id
  [article ::sa/article-partial
   project-id ::sc/sql-serial-id
   & [conn] (s/? any?)]
  (q/create :article (-> (article-to-sql article conn)
                         (assoc :project-id project-id))
            :returning :article-id))

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
   note-name string?
   content (s/nilable string?)]
  (let [{:keys [project-id project-note-id] :as pnote}
        (q/find-one [:article :a] {:a.article-id article-id :pn.name note-name}
                    :pn.*, :join [[[:project :p]       :a.project-id]
                                  [[:project-note :pn] :p.project-id]])
        anote (q/find-one [:article :a] {:a.article-id article-id
                                         :an.user-id user-id
                                         :pn.name note-name}
                          :an.*, :join [[[:article-note :an] :a.article-id]
                                        [[:project-note :pn] :an.project-note-id]])]
    (assert pnote "note type not defined in project")
    (assert project-id "project-id not found")
    (db/with-clear-project-cache project-id
      (let [fields {:article-id article-id
                    :user-id user-id
                    :project-note-id project-note-id
                    :content content
                    :updated-time db/sql-now}]
        (if (nil? anote)
          (q/create :article-note fields, :returning :*)
          (first (q/modify :article-note {:article-id article-id
                                          :user-id user-id
                                          :project-note-id project-note-id}
                           fields, :returning :*)))))))

(defn-spec article-user-notes-map (s/map-of int? any?)
  [project-id ::sc/sql-serial-id
   article-id ::sa/article-id]
  (db/with-project-cache project-id [:article article-id :notes :user-notes-map]
    (->> (q/find [:article :a] {:a.article-id article-id} [:an.* :pn.name]
                 :join [[[:article-note :an] :a.article-id]
                        [[:project-note :pn] :an.project-note-id]])
         (group-by :user-id)
         (map-values #(->> % (index-by :name) (map-values :content))))))

(defn-spec remove-article-flag nil?
  [article-id ::sa/article-id
   flag-name string?]
  (q/delete :article-flag {:article-id article-id :flag-name flag-name})
  nil)

(defn-spec set-article-flag map?
  [article-id ::sa/article-id
   flag-name string?
   disable? boolean?
   & [meta] (s/? (s/cat :meta map?))]
  (db/with-transaction
    (remove-article-flag article-id flag-name)
    (q/create :article-flag {:article-id article-id
                             :flag-name flag-name
                             :disable disable?
                             :meta (some-> meta db/to-jsonb)}
              :returning :*)))

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
      (q/find-one :label-predicts {:article-id article-id
                                   :label-id (project-overall-label-id project-id)
                                   :predict-run-id predict-run-id
                                   :stage 1
                                   :label-value "TRUE"}
                  :val))))

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
