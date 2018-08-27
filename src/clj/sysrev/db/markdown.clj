(ns sysrev.db.markdown
  (:require [honeysql.helpers :as sqlh :refer [insert-into delete-from sset values select from join left-join where]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute]]))

(defn create-project-description!
  "Create a new project description for project-id using markdown"
  [project-id markdown]
  (let [markdown-id (-> (insert-into :markdown)
                        (values [{:string markdown}])
                        (returning :id)
                        do-query
                        first
                        :id)]
    (-> (insert-into :project_description)
        (values [{:project-id project-id
                  :markdown-id markdown-id}])
        do-execute
        first)))

(defn read-project-description
  "Get the project description for project-id"
  [project-id]
  (-> (select :md.string)
      (from [:project_description :pd])
      (join [:markdown :md]
            [:= :pd.markdown_id :md.id])
      (where [:= :pd.project_id project-id])
      do-query
      first
      :string))

(defn update-project-description!
  "Update the project description for project-id with markdown"
  [project-id markdown]
  (let [markdown-id (-> (select :*)
                        (from :project_description)
                        (where [:= :project_id project-id])
                        do-query
                        first
                        :markdown-id)]
    (-> (sqlh/update :markdown)
        (sset {:string markdown})
        (where [:= :id markdown-id])
        do-execute)))

(defn delete-project-description!
  "Delete the project description"
  [project-id]
  (let [markdown-id (-> (select :*)
                        (from :project_description)
                        (where [:= :project_id project-id])
                        do-query
                        first
                        :markdown-id)]
    (-> (delete-from :markdown)
        (where [:= :id markdown-id])
        do-execute)))
