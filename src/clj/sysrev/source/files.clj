(ns sysrev.source.files
  (:require [me.raynes.fs :as fs]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.datapub-client.interface.queries :as dpcq]
            [sysrev.db.core :as db]
            [sysrev.json.interface :as json]
            [sysrev.postgres.interface :as pg]
            [sysrev.source.core :as source]
            [sysrev.util :as util]))

(defn create-source! [sr-context project-id dataset-id]
  (->> {:insert-into :project-source
        :values [{:dataset-id dataset-id
                  :import-date :%now
                  :meta (pg/jsonb-pgobject {:importing-articles? true})
                  :project-id project-id}]
        :returning :source-id}
       (db/execute-one! sr-context)
       :project-source/source-id))

(defn get-article-data! [sr-context entity-id]
  (->> {:select :*
        :from :article-data
        :where [:and
                [:= :datasource-name "datapub"]
                [:= :external-id (pg/jsonb-pgobject entity-id)]]}
       (db/execute-one! sr-context)))

(defn create-article-data! [sr-context {:keys [content dataset-id entity-id title]}]
  {:pre [(map? sr-context) (string? entity-id) (string? title)
         (or (nil? content) (string? content))]}
  (->> {:insert-into :article-data
        :returning :*
        :values [{:article-subtype "entity"
                  :article-type "datapub"
                  :content content
                  :dataset-id dataset-id
                  :datasource-name "datapub"
                  :external-id (pg/jsonb-pgobject entity-id)
                  :title title}]}
       (db/execute-one! sr-context)))

(defn goc-article-data!
  "Get or create an article-data row for an entity."
  [sr-context {:keys [entity-id] :as m}]
  (db/with-tx [sr-context sr-context]
    (or (get-article-data! sr-context entity-id)
        (create-article-data! sr-context m))))

(defn create-article!
  [sr-context project-id source-id article-data-id]
  (db/with-tx [sr-context sr-context]
    (when-let [article-id (->> {:insert-into :article
                                :on-conflict []
                                :do-nothing []
                                :returning :article-id
                                :values [{:article-data-id article-data-id
                                          :project-id project-id}]}
                               (db/execute-one! sr-context)
                               :article/article-id)]
      (->> {:insert-into :article-source
            :values [{:article-id article-id :source-id source-id}]}
           (db/execute-one! sr-context))
      nil)))

(defn create-entity! [{:keys [datapub-opts dataset-id file]}]
  (let [{:keys [content-type filename tempfile]} file
        fname (fs/base-name filename)]
    (-> {:contentUpload (if (= "application/json" content-type)
                          (slurp tempfile)
                          tempfile)
         :datasetId dataset-id
         :mediaType content-type
         :metadata (when (not= "application/json" content-type)
                     (json/write-str {:filename fname}))}
        (dpc/create-dataset-entity! "id" datapub-opts)
        :id)))

(defn create-entities! [sr-context project-id source-id dataset-id files]
  (let [datapub-opts (source/datapub-opts sr-context :upload? true)]
    (doseq [{:keys [filename] :as file} files]
      (let [entity-id (create-entity! {:datapub-opts datapub-opts
                                       :dataset-id dataset-id
                                       :file file})]
        (db/with-tx [sr-context sr-context]
          (let [article-data-id (-> sr-context
                                    (goc-article-data!
                                     {:dataset-id dataset-id
                                      :entity-id entity-id
                                      :content nil
                                      :title (fs/base-name filename)})
                                    :article-data/article-data-id)]
            (create-article! sr-context project-id source-id article-data-id)))))))

(defn import! [sr-context project-id files & {:keys [sync?]}]
  (let [dataset-id (:id (dpc/create-dataset!
                         {:description (str "Files uploaded for project " project-id)
                          :name (random-uuid)
                          :public false}
                         "id"
                         (source/datapub-opts sr-context)))
        source-id (create-source! sr-context project-id dataset-id)]
    (db/clear-project-cache project-id)
    ((if sync? deref identity)
     (future
       (util/log-errors
        (create-entities! sr-context project-id source-id dataset-id files)
        (source/alter-source-meta source-id #(assoc % :importing-articles? false)))))
    {:success true}))

(defn import-entities! [sr-context project-id source-id dataset-id entity-ids]
  (doseq [entity-id entity-ids]
    (db/with-tx [sr-context sr-context]
      (let [article-data-id (-> sr-context
                                (goc-article-data!
                                 {:dataset-id dataset-id
                                  :entity-id entity-id
                                  :content nil
                                  :title (str entity-id)})
                                :article-data/article-data-id)]
        (create-article! sr-context project-id source-id article-data-id)))))

(defn get-dataset-entity-ids [datapub-opts dataset-id & [cursor]]
  (lazy-seq
   (let [{edges :edges
          {:keys [endCursor hasNextPage]} :pageInfo}
         #__ (-> datapub-opts
                 (assoc :query (dpcq/q-dataset#entities "edges{node{id}} pageInfo{endCursor hasNextPage}")
                        :variables {:after cursor
                                    :id dataset-id})
                 dpc/execute!
                 :data :dataset :entities)]
     (concat
      (mapv (comp :id :node) edges)
      (when hasNextPage
        (get-dataset-entity-ids datapub-opts dataset-id endCursor))))))

(defn import-project-source-articles! [sr-context source-id]
  (let [{:project-source/keys [dataset-id project-id]}
        #__ (->> {:select [:dataset-id :project-id]
                  :from :project-source
                  :where [:= :source-id source-id]}
                 (db/execute-one! sr-context))]
    (->> (get-dataset-entity-ids (source/datapub-opts sr-context) dataset-id)
         (import-entities! sr-context project-id source-id dataset-id))))

(defn get-jobs [sr-context type]
  (->> {:select :*
        :from :job
        :where [:and
                [:= :status "new"]
                [:= :type type]]}
       (db/execute! sr-context)))

(defn import-from-job-queue! [sr-context]
  (util/log-errors
   (doseq [{:job/keys [id payload]}
           (get-jobs sr-context "import-project-source-articles")]
     (import-project-source-articles! sr-context (:source-id payload))
     (source/alter-source-meta
      (:source-id payload) #(assoc % :importing-articles? false))
     (->> {:update :job
           :set {:status "done"}
           :where [:= :id id]}
          (db/execute-one! sr-context)))))
