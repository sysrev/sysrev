(ns sysrev.db.markdown
  (:require [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction clear-project-cache]]))

(defn project-description-markdown-id [project-id]
  (-> (select :*)
      (from :project-description)
      (where [:= :project-id project-id])
      do-query
      first
      :markdown-id))

(defn create-markdown-entry [markdown]
  (-> (insert-into :markdown)
      (values [{:string markdown}])
      (returning :id)
      do-query first :id))

(defn set-project-description!
  "Sets value for a project description."
  [project-id value]
  (try
    (with-transaction
      (let [markdown-id (project-description-markdown-id project-id)]
        (cond (empty? value)
              ;; delete entry
              (when markdown-id
                (-> (delete-from :markdown)
                    (where [:= :id markdown-id])
                    do-execute)
                (-> (delete-from :project-description)
                    (where [:and
                            [:= :project-id project-id]
                            [:= :markdown-id markdown-id]])
                    do-execute))

              (integer? markdown-id)
              ;; update entry
              (-> (sqlh/update :markdown)
                  (sset {:string value})
                  (where [:= :id markdown-id])
                  do-execute)

              :else
              ;; create entry
              (let [markdown-id (create-markdown-entry value)]
                (-> (insert-into :project-description)
                    (values [{:project-id project-id
                              :markdown-id markdown-id}])
                    do-execute)))))
    (finally
      (clear-project-cache project-id))))

(defn read-project-description
  "Get the project description for project-id"
  [project-id]
  (-> (select :md.string)
      (from [:project-description :pd])
      (join [:markdown :md]
            [:= :pd.markdown-id :md.id])
      (where [:= :pd.project-id project-id])
      do-query first :string))
