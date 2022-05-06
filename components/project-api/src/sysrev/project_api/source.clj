(ns sysrev.project-api.source
  (:require [com.walmartlabs.lacinia.resolve :as resolve]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.lacinia.interface :as sl]
            [sysrev.postgres.interface :as pg]
            [sysrev.project-api.core :as core]))

(defn get-project-source [context {:keys [id]} _]
  (sl/with-tx-context [context context]
    (or
     (core/check-not-blank id "id")
     (core/dev-key-check context)
     (let [{:project-source/keys [project-id]
            :as source}
           #__ (->> {:select :project-id
                     :from :project-source
                     :where [:= :source-id (parse-long id)]}
                    (sl/execute-one! context))]
       (when source
         {:id id
          :project {:id (str project-id)}})))))

(defn resolve-project-source-field [context _ val]
  (get-project-source context (-> val vals first) nil))

(defn insert-source! [context project-id dataset-id]
  (->> {:insert-into :project-source
        :values [{:dataset-id dataset-id
                  :import-date :%now
                  :meta (pg/jsonb-pgobject {:importing-articles? true})
                  :project-id project-id}]
        :returning :source-id}
       (sl/execute-one! context)
       :project-source/source-id))

(defn insert-job! [context source-id]
  (->> {:insert-into :job
        :values [{:payload (pg/jsonb-pgobject {:source-id source-id})
                  :status "new"
                  :type "import-project-source-articles"}]}
       (sl/execute-one! context)))

(defn create-project-source!
  [context {{:keys [create]} :input} _]
  (sl/with-tx-context [context context]
    (let [{:keys [datasetId projectId]} create
          project-id (parse-long projectId)]
      (or
       (core/check-not-blank datasetId "datasetId")
       (core/check-not-blank projectId "projectId")
       (core/dev-key-check context)
       (when-not (sl/execute-one! context {:select :project-id
                                           :from :project
                                           :where [:= :project-id project-id]})
         (resolve/resolve-as
          nil
          {:projectId projectId
           :message "Project does not exist"}))
       (when-not (dpc/get-dataset datasetId "id"
                                  :auth-token (core/bearer-token context)
                                  :endpoint (core/graphql-endpoint context))
         (resolve/resolve-as
          nil
          {:datasetId datasetId
           :message "Dataset does not exist"}))
       (let [source-id (insert-source! context project-id datasetId)]
         (insert-job! context source-id)
         {:projectSource {:id (str source-id)}})))))
