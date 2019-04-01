(ns sysrev.db.groups
  (:require
   [honeysql-postgres.helpers :refer [returning]]
   [honeysql.helpers :as sqlh :refer [select from where insert-into values sset join modifiers]]
   [sysrev.db.core :refer [do-query do-execute sql-now to-sql-array]]
   [sysrev.db.users :as users]))

(defn get-group-id
  "Given a group-name, get the group-id associated with it"
  [group-name]
  (-> (select :id)
      (from :groups)
      (where [:= :group-name group-name])
      do-query first :id))

(defn get-group-name
  "Given a group-id, the the group name"
  [group-id]
  (-> (select :group-name)
      (from :groups)
      (where [:= :id group-id])
      do-query first :group-name))

(defn create-web-user-group!
  "Create an association between group-name and user-id in web-user-group with optional :permissions vector that default to {'member'}"
  [user-id group-name & {:keys [permissions]}]
  (-> (insert-into :web-user-group)
      (values [(cond-> {:user-id user-id
                        :group-id (get-group-id group-name)}
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
              [:= :group-id (get-group-id group-name)]])
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
                                   [:= :group-id (get-group-id group-name)]])
                           do-query
                           (->> (map :user-id)))]
    (if-not (empty? users-in-group)
      (doall (map #(assoc % :primary-email-verified (users/verified-primary-email? (:email %)))
                  (users/get-users-public-info users-in-group)))
      [])))

(defn user-active-in-group?
  "Is the user-id active in group-name?"
  [user-id group-name]
  (-> (select :active)
      (from :web-user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id (get-group-id group-name)]])
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
