(ns sysrev.db.files
  (:require [sysrev.db.core :as db :refer
             [do-query do-query-map do-execute
              sql-now to-sql-array to-jsonb sql-cast
              with-query-cache clear-query-cache
              with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :refer
             [project-labels project-overall-label-id project-settings]]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.shared.labels :refer [cleanup-label-answer]]
            [sysrev.util :refer [crypto-rand crypto-rand-nth]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defrecord Filerec [project-id file-id name content-md5 etag])

(defn insert-file-rec [rec]
  (-> (insert-into :filestore)
      (values [rec])
      (do-execute)))

(defn list-files-for-project [project-id]
  (->>
    (-> (select :*)
        (from :filestore)
        (where [:and
                [:= nil :delete-time]
                [:= :project-id project-id]])
        (order-by [[:ordering :asc :nulls-first] [:upload-time :asc]])
        (do-query))
    (mapv map->Filerec)))


(defn file-by-id [id project-id]
  (->>
    (-> (select :*)
        (from :filestore)
        (where [:and
                [:= :file-id id]
                ; Require file owned by given project.
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
      (do-execute)))
