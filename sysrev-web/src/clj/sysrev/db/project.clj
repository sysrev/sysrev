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

(defn set-member-permissions
  "Change the permissions for a project member."
  [project-id user-id permissions]
  (-> (sqlh/update :project-member)
      (sset {:permissions (to-sql-array "text" permissions)})
      (where [:and
              [:= :project-id project-id]
              [:= :user-id user-id]])
      (returning :user-id :permissions)
      do-query))

(defn create-project
  "Create a new project entry."
  [project-name]
  (let [project {:name project-name
                 :enabled true
                 :project-uuid (UUID/randomUUID)}
        project-id (-> (insert-into :project)
                       (values [project])
                       (returning :project-id)
                       do-query
                       first)]
    project-id))

(defn delete-project
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id]
  (-> (delete-from :project)
      (where [:= :project-id project-id])
      do-execute))

(defn get-project-summaries
  "Returns a sequence of summary maps for every project."
  []
  (let [projects
        (->> (-> (select :*)
                 (from :project)
                 do-query)
             (group-by :project-id)
             (map-values first))
        members
        (->> (-> (select :u.user-id :u.email :m.permissions :m.project-id)
                 (from [:project-member :m])
                 (join [:web-user :u]
                       [:= :u.user-id :m.user-id])
                 do-query)
             (group-by :project-id)
             (map-values
              (fn [pmembers]
                (->> pmembers
                     (mapv #(dissoc % :project-id))))))]
    (->> projects
         (map-values
          #(assoc % :members
                  (get members (:project-id %) []))))))

(defn get-default-project
  "Selects a fallback project to use as a default. Intended only for dev use."
  []
  (-> (select :*)
      (from :project)
      (order-by [:project-id :asc])
      (limit 1)
      do-query
      first))

(defn ensure-user-member-entries
  "Ensures that each user account is a member of at least one project, by
  adding project-less users to `project-id` or default project."
  [& [project-id]]
  (let [project-id
        (or project-id
            (:project-id (get-default-project)))
        user-ids
        (->>
         (-> (select :u.user-id)
             (from [:web-user :u])
             (where
              [:not
               [:exists
                (-> (select :*)
                    (from [:project-member :m])
                    (where [:= :m.user-id :u.user-id]))]])
             do-query)
         (map :user-id))]
    (->> user-ids
         (mapv #(add-user-to-project project-id %)))))

(defn ensure-user-default-project-ids
  "Ensures that a default-project-id value is set for all users which belong to
  at least one project. ensure-user-member-entries should be run before this."
  []
  (let [user-ids
        (->>
         (-> (select :u.user-id)
             (from [:web-user :u])
             (where
              [:and
               [:= :u.default-project-id nil]
               [:exists
                (-> (select :*)
                    (from [:project-member :m])
                    (where [:= :m.user-id :u.user-id]))]])
             do-query)
         (map :user-id))]
    (doall
     (for [user-id user-ids]
       (let [project-id
             (-> (select :project-id)
                 (from [:project-member :m])
                 (where [:= :m.user-id user-id])
                 (order-by [:join-date :asc])
                 (limit 1)
                 do-query
                 first
                 :project-id)]
         (-> (sqlh/update :web-user)
             (sset {:default-project-id project-id})
             (where [:= :user-id user-id])
             do-execute))))))

(defn ensure-entry-uuids
  "Creates uuid values for database entries with none set."
  []
  (let [project-ids
        (->> (-> (select :project-id)
                 (from :project)
                 (where [:= :project-uuid nil])
                 do-query)
             (map :project-id))
        user-ids
        (->> (-> (select :user-id)
                 (from :web-user)
                 (where [:= :user-uuid nil])
                 do-query)
             (map :user-id))]
    (->> project-ids
         (mapv
          #(-> (sqlh/update :project)
               (sset {:project-uuid (UUID/randomUUID)})
               (where [:= :project-id %])
               do-execute)))
    (->> user-ids
         (mapv
          #(-> (sqlh/update :web-user)
               (sset {:user-uuid (UUID/randomUUID)})
               (where [:= :user-id %])
               do-execute)))))

(defn ensure-permissions-set
  "Sets default permissions values for entries with null value."
  []
  (let [user-defaults (to-sql-array "text" ["user"])
        member-defaults (to-sql-array "text" ["member"])]
    (-> (sqlh/update :web-user)
        (sset {:permissions user-defaults})
        (where [:= :permissions nil])
        do-execute)
    (-> (sqlh/update :project-member)
        (sset {:permissions member-defaults})
        (where [:= :permissions nil])
        do-execute)
    nil))
