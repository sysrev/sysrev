(ns sysrev.db.project
  (:require
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction
     sql-now to-sql-array with-debug-sql]]
   [sysrev.util :refer [map-values]])
  (:import java.util.UUID))

(defn add-user-to-project
  "Add a user to the list of members of a project."
  [project-id user-id &
   {:keys [permissions]
    :or {permissions ["member"]}}]
  (let [entry {:project-id project-id
               :user-id user-id
               :permissions (to-sql-array "text" permissions)}]
    (-> (insert-into :project-member)
        (values [entry])
        (returning :membership-id)
        do-query)))

(defn remove-project-member
  "Remove a user from a project."
  [project-id user-id]
  (-> (delete-from :project-member)
      (where [:and
              [:= :project-id project-id]
              [:= :user-id user-id]])
      do-execute))

(defn set-user-project-permissions
  "Change the permissions for a project member."
  [project-id user-id permissions]
  (-> (sqlh/update :project_member)
      (sset {:permissions (to-sql-array "text" permissions)})
      (where [:and
              [:= :project_id project-id]
              [:= :user_id user-id]])
      (returning :user_id :permissions)
      do-query))

(defn create-project
  "Create a new project entry."
  [project-name]
  (let [project {:name project-name
                 :enabled true
                 :project_uuid (UUID/randomUUID)}
        project-id (-> (insert-into :project)
                       (values [project])
                       (returning :project_id)
                       do-query
                       first)]
    project-id))

(defn delete-project
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id]
  (-> (delete-from :project)
      (where [:= :project_id project-id])
      do-execute))

(defn get-project-summaries
  "Returns a sequence of summary maps for every project."
  []
  (let [projects
        (->> (-> (select :*)
                 (from :project)
                 do-query)
             (group-by :project_id)
             (map-values first))
        members
        (->> (-> (select :u.user_id :u.email :m.permissions :m.project_id)
                 (from [:project_member :m])
                 (join [:web_user :u]
                       [:= :u.user_id :m.user_id])
                 do-query)
             (group-by :project_id)
             (map-values
              (fn [pmembers]
                (->> pmembers
                     (mapv #(dissoc % :project_id))))))]
    (->> projects
         (map-values
          #(assoc % :members
                  (get members (:project_id %) []))))))

(defn get-default-project
  "Selects a fallback project to use as a default. Intended only for dev use."
  []
  (-> (select :*)
      (from :project)
      (order-by [:project_id :asc])
      (limit 1)
      do-query
      first))

(defn ensure-user-member-entries
  "Ensures that each user account is a member of at least one project, by
  adding project-less users to `project-id` or default project."
  [& [project-id]]
  (let [project-id
        (or project-id
            (:project_id (get-default-project)))
        user-ids
        (->>
         (-> (select :u.user_id)
             (from [:web_user :u])
             (where
              [:not
               [:exists
                (-> (select :*)
                    (from [:project_member :m])
                    (where [:= :m.user_id :u.user_id]))]])
             do-query)
         (map :user_id))]
    (->> user-ids
         (mapv #(add-user-to-project project-id %)))))

(defn ensure-user-default-project-ids
  "Ensures that a default_project_id value is set for all users which belong to
  at least one project. ensure-user-member-entries should be run before this."
  []
  (let [user-ids
        (->>
         (-> (select :u.user_id)
             (from [:web_user :u])
             (where
              [:and
               [:= :u.default_project_id nil]
               [:exists
                (-> (select :*)
                    (from [:project_member :m])
                    (where [:= :m.user_id :u.user_id]))]])
             do-query)
         (map :user_id))]
    (doall
     (for [user-id user-ids]
       (let [project-id
             (-> (select :project_id)
                 (from [:project_member :m])
                 (where [:= :m.user_id user-id])
                 (order-by [:join_date :asc])
                 (limit 1)
                 do-query
                 first
                 :project_id)]
         (-> (sqlh/update :web_user)
             (sset {:default_project_id project-id})
             (where [:= :user_id user-id])
             do-execute))))))

(defn ensure-entry-uuids
  "Creates uuid values for database entries with none set."
  []
  (let [project-ids
        (->> (-> (select :project_id)
                 (from :project)
                 (where [:= :project_uuid nil])
                 do-query)
             (map :project_id))
        user-ids
        (->> (-> (select :user_id)
                 (from :web_user)
                 (where [:= :user_uuid nil])
                 do-query)
             (map :user_id))]
    (->> project-ids
         (mapv
          #(-> (sqlh/update :project)
               (sset {:project_uuid (UUID/randomUUID)})
               (where [:= :project_id %])
               do-execute)))
    (->> user-ids
         (mapv
          #(-> (sqlh/update :web_user)
               (sset {:user_uuid (UUID/randomUUID)})
               (where [:= :user_id %])
               do-execute)))))
