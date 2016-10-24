(ns sysrev.db.project
  (:require
    [honeysql.core :as sql]
    [honeysql.helpers :as sqlh :refer :all :exclude [update]]
    [honeysql-postgres.format :refer :all]
    [honeysql-postgres.helpers :refer :all]
    [sysrev.db.core :refer [do-query do-execute do-transaction sql-now]])
  (:import (java.sql BatchUpdateException)))


(defn add-user-to-project [project-id user-id]
  (-> (insert-into :project-member)
      (values [project-membership])
      (returning :membership-id)
      do-execute))


(defn create-project [user-id project-name]
  (let [project {:name project-name :enabled true}
        project-id (-> (insert-into :project)
                       (values [project])
                       (returning :project-id)
                       do-query
                       first
                       :project-id)
      project-id]))
