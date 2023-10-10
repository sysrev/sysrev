(ns sysrev.project-api.source
  (:require [sysrev.lacinia.interface :as sl]
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
