(ns sysrev.source.files
  (:require [me.raynes.fs :as fs]
            [sysrev.datapub-client.interface :as dpc]
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

(defn create-article-data! [sr-context entity-id title content]
  {:pre [(map? sr-context) (string? entity-id) (string? title)
         (or (nil? content) (string? content))]}
  (->> {:insert-into :article-data
        :returning :*
        :values [{:article-subtype "entity"
                  :article-type "datapub"
                  :content content
                  :datasource-name "datapub"
                  :external-id (pg/jsonb-pgobject entity-id)
                  :title title}]}
       (db/execute-one! sr-context)))

(defn goc-article-data!
  "Get or create an article-data row for an entity."
  [sr-context entity-id title content]
  (db/with-tx [sr-context sr-context]
    (or (get-article-data! sr-context entity-id)
        (create-article-data! sr-context entity-id title content))))

(defn create-article!
  [sr-context project-id source-id article-data-id]
  (db/with-tx [sr-context sr-context]
    (let [article-id (->> {:insert-into :article
                           :returning :article-id
                           :values [{:article-data-id article-data-id
                                     :project-id project-id}]}
                          (db/execute-one! sr-context)
                          :article/article-id)]
      (->> {:insert-into :article-source
            :values [{:article-id article-id :source-id source-id}]}
           (db/execute-one! sr-context))
      nil)))

(defn create-entities! [sr-context project-id source-id dataset-id files]
  (let [datapub-opts (source/datapub-opts sr-context)]
    (doseq [{:keys [filename tempfile]} files]
      (db/with-tx [sr-context sr-context]
        (let [fname (fs/base-name filename)
              entity-id (-> {:contentUpload tempfile
                             :datasetId dataset-id
                             :mediaType "application/pdf"
                             :metadata (json/write-str {:filename fname})}
                            (dpc/create-dataset-entity! "id" datapub-opts)
                            :id)
              article-data-id (:article-data/article-data-id
                               (goc-article-data! sr-context entity-id fname nil))]
          (create-article! sr-context project-id source-id article-data-id))))))

(defn import! [sr-context project-id files]
  (let [dataset-id (:id (dpc/create-dataset!
                         {:description (str "Files uploaded for project " project-id)
                          :name (random-uuid)
                          :public false}
                         "id"
                         (source/datapub-opts sr-context)))
        source-id (create-source! sr-context project-id dataset-id)]
    (db/clear-project-cache project-id)
    (future
      (create-entities! sr-context project-id source-id dataset-id files)
      (source/alter-source-meta source-id #(assoc % :importing-articles? false)))
    {:success true}))
