(ns sysrev.db.project
  (:require
    [honeysql.core :as sql]
    [honeysql.helpers :as sqlh :refer :all :exclude [update]]
    [honeysql-postgres.format :refer :all :exclude [partition-by]]
    [honeysql-postgres.helpers :refer :all]
    [sysrev.db.core :refer [do-query do-execute do-transaction sql-now]]))

(defn add-user-to-project [project-id user-id]
  (let [project-membership {:project-id project-id :user-id user-id}]
    (-> (insert-into :project-member)
        (values [project-membership])
        (returning :membership-id)
        do-execute)))


(defn create-project [user-id project-name]
  (let [project {:name project-name :enabled true}
        project-id (-> (insert-into :project)
                       (values [project])
                       (returning :project-id)
                       do-query
                       first)]
      project-id))
