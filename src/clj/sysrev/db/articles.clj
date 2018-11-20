(ns sysrev.db.articles
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.util :as u :refer [in? map-values]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defn article-to-sql
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article & [conn]]
  (try
    (-> article
        (update :authors #(db/to-sql-array "text" % conn))
        (update :keywords #(db/to-sql-array "text" % conn))
        (update :urls #(db/to-sql-array "text" % conn))
        (update :document-ids #(db/to-sql-array "text" % conn)))
    (catch Throwable e
      (log/warn "article-to-sql: error converting article")
      (log/warn "article =" (pr-str article))
      (throw e))))
;;
(s/fdef article-to-sql
        :args (s/cat :article ::sa/article-partial
                     :conn (s/? any?))
        :ret map?)

(defn add-article [article project-id & [conn]]
  (let [project-id (q/to-project-id project-id)]
    (-> (insert-into :article)
        (values [(merge (article-to-sql article conn)
                        {:project-id project-id})])
        (returning :article-id)
        do-query first :article-id)))
;;
(s/fdef add-article
        :args (s/cat :article ::sa/article-partial
                     :project-id ::sc/project-id
                     :conn (s/? any?))
        :ret (s/nilable ::sc/article-id))

(defn add-articles [articles project-id & [conn]]
  (if (empty? articles)
    []
    (let [project-id (q/to-project-id project-id)
          entries (->> articles
                       (mapv #(merge (article-to-sql % conn)
                                     {:project-id project-id})))]
      (-> (insert-into :article)
          (values entries)
          (returning :article-id)
          (->> do-query (mapv :article-id))))))

(defn set-user-article-note [article-id user-id note-name content]
  (let [article-id (q/to-article-id article-id)
        user-id (q/to-user-id user-id)
        pnote (-> (q/select-article-by-id article-id [:pn.*])
                  (merge-join [:project :p]
                              [:= :p.project-id :a.project-id])
                  (q/with-project-note note-name)
                  do-query first)
        anote (-> (q/select-article-by-id article-id [:an.*])
                  (q/with-article-note note-name user-id)
                  do-query first)]
    (assert pnote "note type not defined in project")
    (assert (:project-id pnote) "project-id not found")
    (try
      (let [fields {:article-id article-id
                    :user-id user-id
                    :project-note-id (:project-note-id pnote)
                    :content content
                    :updated-time (db/sql-now)}]
        (if (nil? anote)
          (-> (sqlh/insert-into :article-note)
              (values [fields])
              (returning :*)
              do-query)
          (-> (sqlh/update :article-note)
              (where [:and
                      [:= :article-id article-id]
                      [:= :user-id user-id]
                      [:= :project-note-id (:project-note-id pnote)]])
              (sset fields)
              (returning :*)
              do-query)))
      (finally
        (clear-project-cache (:project-id pnote))))))
;;
(s/fdef set-user-article-note
        :args (s/cat :article-id ::sc/article-id
                     :user-id ::sc/user-id
                     :note-name string?
                     :content (s/nilable string?))
        :ret (s/nilable map?))

(defn article-user-notes-map [project-id article-id]
  (let [project-id (q/to-project-id project-id)
        article-id (q/to-article-id article-id)]
    (with-project-cache
      project-id [:article article-id :notes :user-notes-map]
      (->>
       (-> (q/select-article-by-id article-id [:an.* :pn.name])
           (q/with-article-note)
           do-query)
       (group-by :user-id)
       (map-values
        #(->> %
              (group-by :name)
              (map-values first)
              (map-values :content)))))))
;;
(s/fdef article-user-notes-map
        :args (s/cat :project-id ::sc/project-id
                     :article-id ::sc/article-id)
        :ret (s/nilable map?))

(defn remove-article-flag [article-id flag-name]
  (-> (delete-from :article-flag)
      (where [:and
              [:= :article-id article-id]
              [:= :flag-name flag-name]])
      do-execute))
;;
(s/fdef remove-article-flag
        :args (s/cat :article-id ::sc/article-id
                     :flag-name ::sa/flag-name)
        :ret ::sc/sql-execute)

(defn set-article-flag [article-id flag-name disable? & [meta]]
  (remove-article-flag article-id flag-name)
  (-> (insert-into :article-flag)
      (values [{:article-id article-id
                :flag-name flag-name
                :disable disable?
                :meta (when meta (db/to-jsonb meta))}])
      (returning :*)
      do-query first))
;;
(s/fdef set-article-flag
        :args (s/cat :article-id ::sc/article-id
                     :flag-name ::sa/flag-name
                     :disable? boolean?
                     :meta (s/? ::sa/meta))
        :ret map?)

(defn article-review-status [article-id]
  (let [entries
        (-> (q/select-article-by-id
             article-id [:al.user-id :al.resolve :al.answer])
            (q/join-article-labels)
            (q/join-article-label-defs)
            (q/filter-valid-article-label true)
            (q/filter-overall-label)
            do-query)]
    (cond
      (empty? entries) :unreviewed
      (some :resolve entries) :resolved
      (= 1 (count entries)) :single
      (= 1 (->> entries (map :answer) distinct count)) :consistent
      :else :conflict)))

(defn article-locations-map [article-id]
  (->> (q/query-article-locations-by-id
        article-id [:al.source :al.external-id])
       (group-by :source)))

(defn article-flags-map [article-id]
  (-> (q/select-article-by-id
       article-id
       (db/table-fields :aflag [:flag-name :disable :date-created :meta]))
      (q/join-article-flags)
      (->> do-query
           (group-by :flag-name)
           (map-values first)
           (map-values #(dissoc % :flag-name)))))

(defn article-score
  [article-id & {:keys [predict-run-id] :as opts}]
  (-> (q/select-article-by-id article-id [:a.article-id])
      (q/with-article-predict-score
        (or predict-run-id (q/article-latest-predict-run-id article-id)))
      do-query first :score))

(defn get-article
  "Queries for article data by id, with data from other tables included.

  `items` is an optional sequence of keywords configuring which values
  to include, defaulting to all possible values.

  `predict-run-id` allows for specifying a non-default prediction run to
  use for the prediction score."
  [article-id & {:keys [items predict-run-id]
                 :or {items [:locations :score :review-status :flags]}
                 :as opts}]
  (assert (->> items (every? #(in? [:locations :score :review-status :flags] %))))
  (let [article (-> (q/query-article-by-id article-id [:a.*])
                    (dissoc :text-search))
        get-item (fn [item-key f] (when (in? items item-key)
                                    (fn [] {item-key (f)})))
        item-values
        (when (not-empty article)
          ;; For each key in `items` run function to get corresponding value,
          ;; then merge all together into a single map.
          ;; (load items in parallel using `pcalls`)
          (->> [(get-item :locations
                          #(article-locations-map article-id))
                (get-item :score
                          #(or (article-score
                                article-id :predict-run-id predict-run-id)
                               0.0))
                (get-item :review-status
                          #(article-review-status article-id))
                (get-item :flags
                          #(article-flags-map article-id))]
               (remove nil?) (apply pcalls) doall (apply merge {})))]
    (when (not-empty article)
      (merge article item-values))))

(defn find-project-article-by-uuid
  "Query for an article entry in a project by matching on either
  `article-uuid` or `parent-article-uuid`."
  [project-id article-uuid &
   {:keys [fields include-disabled?]
    :or {fields [:*]
         include-disabled? true}}]
  (-> (q/select-article-where
       project-id
       [:or
        [:= :a.article-uuid article-uuid]
        [:= :a.parent-article-uuid article-uuid]]
       fields
       {:include-disabled? include-disabled?})
      do-query
      first))

(defn flag-project-articles-by-uuid
  [project-id article-uuids flag-name disable? & [meta]]
  (doseq [article-uuid article-uuids]
    (let [{:keys [article-id]}
          (find-project-article-by-uuid
           project-id article-uuid :fields [:a.article-id])]
      (if (nil? article-id)
        (log/warn (format "article not found: %s" article-uuid))
        (set-article-flag
         article-id flag-name disable? meta)))))

(defn to-article
  "Queries by id argument or returns article map argument unmodified."
  [article-or-id]
  (let [in (s/conform ::sa/article-or-id article-or-id)]
    (if (= in ::s/invalid)
      nil
      (let [[t v] in]
        (cond
          (= t :map) v
          (= t :id) (let [[_ id] v]
                      (->> (get-article id)
                           (s/assert (s/nilable ::sa/article))))
          :else nil)))))
;;
(s/fdef to-article
        :args (s/cat :article-or-id ::sa/article-or-id)
        :ret (s/nilable ::sa/article))

(defn copy-project-article [src-project-id dest-project-id article-id]
  (try
    (if-let [article (get-article article-id)]
      (if (= (:project-id article) src-project-id)
        (if-let [new-article-id
                 (add-article (-> article (dissoc :article-id
                                                  :article-uuid
                                                  :project-id
                                                  :duplicate-of
                                                  :locations
                                                  :review-status
                                                  :score
                                                  :flags))
                              dest-project-id)]
          (let [locations
                (-> (q/select-project-articles
                     src-project-id [:aloc.article-id :aloc.source :aloc.external-id])
                    (q/join-article-locations)
                    (merge-where [:= :aloc.article-id article-id])
                    (->> do-query
                         (mapv #(assoc % :article-id new-article-id))))]
            (when-not (empty? locations)
              (-> (sqlh/insert-into :article-location)
                  (values (vec locations))
                  do-execute))
            :success)
          :add-article-failed)
        :wrong-article-project)
      :not-found)
    (catch Throwable e
      :error)
    (finally
      (when dest-project-id
        (clear-project-cache dest-project-id)))))

(defn articles-have-labels?
  "Does the coll of article ids have labels associated with them?"
  [coll]
  (boolean (> (-> (select :%count.*)
                  (from :article-label)
                  (where [:in :article_id coll])
                  do-query
                  first
                  :count)
              0)))

(s/fdef articles-have-labels?
        :args (s/cat :coll coll?)
        :ret boolean?)

(defn article-location-urls [locations]
  (let [sources [:pubmed :doi :pii :nct]]
    (->>
     sources
     (map
      (fn [source]
        (let [entries (get locations (name source))]
          (->>
           entries
           (map
            (fn [{:keys [external-id]}]
              (case (keyword source)
                :pubmed (str "https://www.ncbi.nlm.nih.gov/pubmed/?term=" external-id)
                :doi (str "https://dx.doi.org/" external-id)
                :pmc (str "https://www.ncbi.nlm.nih.gov/pmc/articles/" external-id "/")
                :nct (str "https://clinicaltrials.gov/ct2/show/" external-id)
                nil)))))))
     (apply concat)
     (filter identity))))

(defn project-prediction-scores
  "Given a project-id, return the prediction scores for all articles"
  [project-id & {:keys [include-disabled? predict-run-id]
                 :or {include-disabled? false
                      predict-run-id (-> (select :predict-run-id)
                                         (from [:predict-run :pr])
                                         (where [:= :pr.project-id project-id])
                                         (order-by [:pr.create-time :desc])
                                         (limit 1)
                                         do-query
                                         first
                                         :predict-run-id)}}]
  (-> (select :lp.article-id :lp.val)
      (from [:label_predicts :lp])
      (join [:article :a] [:= :lp.article-id :a.article-id])
      (where [:and
              [:= :a.project_id project-id]
              [:= :lp.predict_run_id predict-run-id]
              (when (not include-disabled?)
                [:= :a.enabled true])])
      do-query))

(defn article-ids-to-uuids [article-ids]
  (->> (partition-all 500 article-ids)
       (mapv (fn [article-ids]
               (-> (select :article-uuid)
                   (from [:article :a])
                   (where [:in :article-id article-ids])
                   (->> do-query (map :article-uuid)))))
       (apply concat)))

(defn article-pmcid
  "Given an article id, return it's pmcid. Returns nil if it does not exist"
  [article-id]
  (try
      (-> (select :raw)
          (from :article)
          (where [:= :article_id article-id])
          do-query
          first
          :raw
          (->> (re-find #"PMC\d+")))
      (catch Throwable e
        nil)))

(defn pmcid-in-s3store?
  "Given a pmcid, do we have a file associated with it in the s3store?"
  [pmcid]
  (boolean (try (-> (select :pmcid)
                    (from :pmcid_s3store)
                    (where [:= :pmcid pmcid])
                    do-query
                    first)
                (catch Throwable e
                  nil))))

(defn pmcid->s3store-id
  "Given a PMCID, return a s3store-id associated with it, if any"
  [pmcid]
  (-> (select :s3_id)
      (from :pmcid_s3store)
      (where [:= :pmcid pmcid])
      do-query
      first
      :s3-id))

(defn associate-pmcid-s3store
  "Given a pmcid and an s3store-id, associate the two"
  [pmcid s3store-id]
  (-> (insert-into :pmcid_s3store)
      (values [{:pmcid pmcid
                :s3_id s3store-id}])
      do-execute))
