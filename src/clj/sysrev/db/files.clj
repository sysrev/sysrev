(ns sysrev.db.files
  (:require [sysrev.db.core :as db :refer
             [do-query do-execute sql-now clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :refer [map-values in?]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defrecord Filerec [project-id file-id name content-md5 etag])

(defn insert-file-rec [rec]
  (-> (insert-into :filestore)
      (values [rec])
      (do-execute))
  (when-let [project-id (:project-id rec)]
    (clear-project-cache project-id)))

(defn list-files-for-project [project-id]
  (->>
   (-> (select :*)
       (from :filestore)
       (where [:and
               [:= nil :delete-time]
               [:= :project-id project-id]])
       (order-by [[:ordering (sql/raw "NULLS FIRST")] [:upload-time]])
       do-query)
   (mapv map->Filerec)))

(defn file-by-id [id project-id]
  (->>
   (-> (select :*)
       (from :filestore)
       (where [:and
               [:= :file-id id]
               ;; Require file owned by given project.
               [:= :project-id project-id]])
       (limit 1)
       (do-query)
       (first))
   map->Filerec))

(defn mark-deleted [id project-id]
  (-> (sqlh/update :filestore)
      (sset {:delete-time (sql-now)})
      (where [:and
              [:= :file-id id]
              [:= :project-id project-id]])
      (do-execute))
  (when project-id
    (clear-project-cache project-id)))
