(ns sysrev.db.groups
  (:require
   [honeysql-postgres.helpers :refer [returning]]
   [honeysql.helpers :as sqlh :refer [select from where insert-into values sset join modifiers]]
   [sysrev.db.core :refer [do-query do-execute sql-now to-sql-array]]
   [sysrev.db.users :as users]))

(defn group-name->group-id
  "Given a group-name, get the group-id associated with it"
  [group-name]
  (-> (select :id)
      (from :groups)
      (where [:= :group-name group-name])
      do-query first :id))

(defn group-id->group-name
  "Given a group-id, the the group name"
  [group-id]
  (-> (select :group-name)
      (from :groups)
      (where [:= :id group-id])
      do-query first :group-name))

(defn add-user-to-group!
  "Create an association between group-name and user-id in web-user-group with optional :permissions vector that default to {'member'}"
  [user-id group-id & {:keys [permissions]}]
  (-> (insert-into :web-user-group)
      (values [(cond-> {:user-id user-id
                        :group-id group-id}
                 permissions (assoc :permissions (to-sql-array "text" permissions) ))])
      (returning :id)
      do-query first :id))

(defn read-web-user-group-name
  "Read the id for the web-user-group for user-id and group-name"
  [user-id group-name]
  (-> (select :id :active)
      (from :web-user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id (group-name->group-id group-name)]])
      do-query
      first))

(defn update-web-user-group!
  "Set the boolean active? on group-id"
  [web-user-group-id active?]
  (-> (sqlh/update :web-user-group)
      (sset {:active active?
             :updated (sql-now)})
      (where [:= :id web-user-group-id])
      do-execute))

(defn read-users-in-group
  "Return all of the users in group-name"
  [group-name]
  (let [users-in-group (-> (select :user-id)
                           (from :web-user-group)
                           (where [:and
                                   [:= :active true]
                                   [:= :group-id (group-name->group-id group-name)]])
                           do-query
                           (->> (map :user-id)))]
    (if-not (empty? users-in-group)
      (doall (map #(assoc % :primary-email-verified (users/primary-email-verified? (:user-id %)))
                  (users/get-users-public-info users-in-group)))
      [])))

(defn user-active-in-group?
  "Is the user-id active in group-name?"
  [user-id group-name]
  (-> (select :active)
      (from :web-user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id (group-name->group-id group-name)]])
      do-query
      first
      boolean))

(defn read-groups
  [user-id]
  (-> (select :g.*)
      (modifiers :distinct)
      (from [:web_user_group :wug])
      (join [:groups :g]
            [:= :wug.group_id :g.id])
      (where [:and
              [:= :wug.user_id user-id]
              [:= :wug.active true]])
      do-query))

(defn create-group!
  [group-name]
  (-> (insert-into :groups)
      (values [{:group-name group-name}])
      (returning :id)
      do-query first :id))

(defn create-project-group!
  [project-id group-id]
  (-> (insert-into :project-group)
      (values [{:project-id project-id
                :group-id group-id}])
      (returning :id)
      do-query first :id))

(defn user-group-permission
  "Return the permissions for user-id in group-id"
  [user-id group-id]
  (-> (select :permissions)
      (from :web-user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id group-id]])
      do-query first :permissions))
