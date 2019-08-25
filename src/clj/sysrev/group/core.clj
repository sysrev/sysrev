(ns sysrev.group.core
  (:require [honeysql-postgres.helpers :refer [returning]]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :as db :refer [do-query do-execute sql-now to-sql-array with-transaction]]
            [sysrev.user.core :as user]
            [sysrev.payment.stripe :as stripe]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [index-by]]))

(defn group-name->group-id
  "Given a group-name, get the group-id associated with it"
  [group-name]
  (-> (select :group-id)
      (from :groups)
      (where [:= :group-name group-name])
      do-query first :group-id))

(defn group-id->group-name
  "Given a group-id, the the group name"
  [group-id]
  (-> (select :group-name)
      (from :groups)
      (where [:= :group-id group-id])
      do-query first :group-name))

(defn add-user-to-group!
  "Create a user-group association between group-id and user-id
  with optional :permissions vector (default of [\"member\"])"
  [user-id group-id & {:keys [permissions]}]
  (-> (insert-into :user-group)
      (values [(cond-> {:user-id user-id
                        :group-id group-id}
                 permissions (assoc :permissions (to-sql-array "text" permissions) ))])
      (returning :id)
      do-query first :id))

(defn get-group-owner [group-id]
  (-> (select :user-id)
      (from :user-group)
      (where [:and
              [:= :group-id group-id]
              [:= "owner" :%any.permissions]])
      do-query first :user-id))

(defn read-user-group-name
  "Read the id for the user-group for user-id and group-name"
  [user-id group-name]
  (-> (select :id :enabled)
      (from :user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id (group-name->group-id group-name)]])
      do-query
      first))

(defn set-user-group-enabled!
  "Set boolean enabled status for user-group entry"
  [user-group-id enabled]
  (-> (sqlh/update :user-group)
      (sset {:enabled enabled
             :updated (sql-now)})
      (where [:= :id user-group-id])
      do-execute))

(defn set-user-group-permissions!
  "Set the permissions for user-group-id"
  [user-group-id permissions]
  (-> (sqlh/update :user-group)
      (sset {:permissions (to-sql-array "text" permissions)
             :updated (sql-now)})
      (where [:= :id user-group-id])
      do-execute))

(defn read-users-in-group
  "Return all of the users in group-name"
  [group-name]
  (with-transaction
    (let [users-in-group (-> (select :user-id :permissions)
                             (from :user-group)
                             (where [:and
                                     [:= :enabled true]
                                     [:= :group-id (group-name->group-id group-name)]])
                             do-query)
          users-public-info (->> (map :user-id users-in-group)
                                 (user/get-users-public-info)
                                 (index-by :user-id))]
      (vec (some->> (seq users-in-group)
                    (map #(assoc % :primary-email-verified
                                 (user/primary-email-verified? (:user-id %))))
                    (map #(merge % (get users-public-info (:user-id %)))))))))

(defn user-active-in-group?
  "Test for presence of enabled user-group entry"
  [user-id group-name]
  (with-transaction
    (-> (select :enabled)
        (from :user-group)
        (where [:and
                [:= :user-id user-id]
                [:= :group-id (group-name->group-id group-name)]])
        do-query first :enabled boolean)))

(defn read-groups
  [user-id]
  (-> (select :g.* :ug.permissions)
      (modifiers :distinct)
      (from [:user-group :ug])
      (join [:groups :g] [:= :ug.group-id :g.group-id])
      (where [:and [:= :ug.user-id user-id] [:= :ug.enabled true]])
      do-query))

(defn create-group!
  [group-name]
  (-> (insert-into :groups)
      (values [{:group-name group-name}])
      (returning :group-id)
      do-query first :group-id))

(defn delete-group!
  [group-id]
  (-> (delete-from :groups)
      (where [:= :group-id group-id])
      do-execute))

(defn create-project-group!
  [project-id group-id]
  (-> (insert-into :project-group)
      (values [{:project-id project-id
                :group-id group-id}])
      (returning :id)
      do-query first :id))

(defn delete-project-group!
  [project-id group-id]
  (-> (delete-from :project-group)
      (where [:and
              [:= :project-id project-id]
              [:= :group-id group-id]])
      do-execute))

(defn user-group-permission
  "Return the permissions for user-id in group-id"
  [user-id group-id]
  (-> (select :permissions)
      (from :user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id group-id]])
      do-query first :permissions))

(defn group-projects
  "Return all projects group-id owns"
  [group-id & {:keys [private-projects?]}]
  (-> (select :p.project-id :p.name :p.settings)
      (from [:project :p])
      (join [:project-group :pg]
            [:= :pg.project-id :p.project-id])
      (where [:= :pg.group-id group-id])
      do-query
      (cond->> (not private-projects?) (filter #(-> % :settings :public-access true?)))))

(defn create-sysrev-stripe-customer!
  "Create a stripe customer for group-id"
  [group-id]
  (let [group-name (group-id->group-name group-id)
        stripe-response (stripe/create-customer! :description (str "Sysrev group name: " group-name))
        stripe-customer-id (:id stripe-response)]
    (if-not (nil? stripe-customer-id)
      (try
        (do (-> (sqlh/update :groups)
                (sset {:stripe-id stripe-customer-id})
                (where [:= :group-id group-id])
                do-execute)
            {:success true})
        (catch Throwable e
          {:error {:message (str "Exception in " (util/current-function-name))
                   :exception e}}))
      {:error {:message
               (str "No customer id returned by stripe.com for group-id: " group-id)}})))

(defn get-stripe-id
  "Return the stripe-id for org-id"
  [org-id]
  (-> (select :stripe-id)
      (from :groups)
      (where [:= :group-id org-id])
      do-query first :stripe-id))

(defn group-id-from-url-id [url-id]
  ;; TODO: implement url-id strings for groups
  (sutil/parse-integer url-id))

(defn search-groups
  "Return groups whose name matches search term q"
  [q & {:keys [limit]
        :or {limit 5}}]
  (with-transaction
    (let [user-ids (->> ["SELECT group_id,group_name FROM groups WHERE (group_name ilike ?) AND group_name != 'public-reviewer' ORDER BY group_name LIMIT ?"
                         (str "%" q "%")
                         limit]
                        db/raw-query)]
      ;; check to see if we have results before returning the public info
      user-ids)))
