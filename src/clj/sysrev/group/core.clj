(ns sysrev.group.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [honeysql.helpers :as sqlh]
            [sysrev.db.core :as db :refer [with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.notification.interface :refer [subscribe-to-topic subscriber-for-user
                                                   topic-for-name unsubscribe-from-topic]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.encryption :as enc]
            [sysrev.payment.stripe :as stripe]
            [sysrev.payment.plans :as plans]
            [sysrev.shared.spec.core :as sc]
            [sysrev.user.interface :as user]
            [sysrev.user.interface.spec :as su]
            [sysrev.util :as util :refer [index-by]]))

(defn group-name->id [group-name]
  (q/find-one :groups {:group-name group-name} :group-id))

(defn group-id->name [group-id]
  (q/find-one :groups {:group-id group-id} :group-name))

(s/def ::permissions (s/? string?))

(defn read-users-in-group
  "Return all of the users in group-name"
  [group-name]
  (with-transaction
    (let [users-in-group (q/find :user-group {:enabled true
                                              :group-id (group-name->id group-name)}
                                 [:user-id :permissions])
          users-public-info (->> (map :user-id users-in-group)
                                 (user/get-users-public-info)
                                 (index-by :user-id))]
      (vec (some->> (seq users-in-group)
                    (map #(assoc % :primary-email-verified
                                 (user/primary-email-verified? (:user-id %))))
                    (map #(merge % (get users-public-info (:user-id %)))))))))

(defn-spec get-group-owner (s/nilable int?)
  "Return earliest owner `user-id` among current owners of `group-id`."
  [group-id int?]
  (first (q/find :user-group {:group-id group-id, "owner" :%any.permissions}
                 :user-id, :order-by :created, :limit 1)))

(defn get-premium-members-count [user-id]
  (let [group-ids (q/find [:user-group :ug] {"owner" :%any.permissions :ug.user-id user-id}
                          :ug.group-id, :order-by :ug.created)
        user-ids (-> (sqlh/select :%distinct.ug.user-id)
                     (sqlh/from [:user-group :ug])
                     (sqlh/where [:in :group-id group-ids]
                                 [:= :enabled true])
                     db/do-query)]
    (count user-ids)))

(defn update-premium-members-count! [group-id]
  (let [user-id (get-group-owner group-id)
        {:keys [sub-id nickname] :as plan} (plans/user-current-plan user-id)
        plan-id (:id plan)
        sub-item-id (stripe/get-subscription-item sub-id)]
    (when (contains? #{plans-info/unlimited-org plans-info/unlimited-org-annual} nickname)
      (stripe/update-subscription-item!
        {:id sub-item-id :plan plan-id
         :quantity (get-premium-members-count user-id)}))))

(defn-spec add-user-to-group! nat-int?
  "Create a user-group association between group-id and user-id
  with optional :permissions vector (default of [\"member\"])"
  [user-id ::su/user-id group-id ::sc/group-id
   & {:keys [permissions]} (util/opt-keys ::permissions)]
  (with-transaction
    (-> (subscriber-for-user user-id :create? true :returning :subscriber-id)
        (subscribe-to-topic
         (topic-for-name (str ":group " group-id) :create? true :returning :topic-id)))
    (q/create :user-group (cond-> {:user-id user-id :group-id group-id}
                            permissions (assoc :permissions (db/to-sql-array "text" permissions)))
              :returning :id)
    (update-premium-members-count! group-id)))

(defn read-user-group-name
  "Read the id for the user-group for user-id and group-name"
  [user-id group-name]
  (q/find-one :user-group {:user-id user-id
                           :group-id (group-name->id group-name)}
              [:id :enabled]))

(defn set-user-group-enabled!
  "Set boolean enabled status for user-group entry"
  [user-group-id enabled]
  (with-transaction
    (let [{:keys [group-id user-id] enbld :enabled}
          #__ (q/find-one :user-group {:id user-group-id} [:enabled :group-id :user-id])]
      (when (not= enabled enbld)
        (let [subscriber-id (subscriber-for-user user-id :create? true :returning :subscriber-id)
              topic-id (topic-for-name (str ":group " group-id) :create? true :returning :topic-id)]
          ((if enabled subscribe-to-topic unsubscribe-from-topic) subscriber-id topic-id)))
      (q/modify :user-group {:id user-group-id}
                {:enabled enabled, :updated :%now})
      (update-premium-members-count! group-id))))

(defn set-user-group-permissions!
  "Set the permissions for user-group-id"
  [user-group-id permissions]
  (q/modify :user-group {:id user-group-id}
            {:permissions (db/to-sql-array "text" permissions), :updated :%now}))



(defn user-active-in-group?
  "Test for presence of enabled user-group entry"
  [user-id group-name]
  (with-transaction
    (boolean (q/find-one :user-group
                         {:user-id user-id, :group-id (group-name->id group-name)}
                         :enabled))))

(defn read-groups [user-id]
  (q/find [:user-group :ug] {:ug.user-id user-id :ug.enabled true}
          [:g.* :ug.permissions], :join [[:groups :g] :ug.group-id]
          :prepare #(sqlh/modifiers % :distinct)))

(defn create-group! [group-name]
  (q/create :groups {:group-name group-name}
            :returning :group-id))

(defn group-stripe-id [group-id]
  (q/find-one :groups {:group-id group-id} :stripe-id))

(defn delete-group! [group-id]
  (when-let [stripe-id (group-stripe-id group-id)]
    (when-let [{:keys [sub-id]} (plans/group-current-plan group-id)]
      (stripe/delete-subscription! sub-id))
    (stripe/delete-customer! {:stripe-id stripe-id :user-id (str "group-id: " group-id)}))
  (q/delete :groups {:group-id group-id}))

(defn create-project-group! [project-id group-id]
  (q/create :project-group {:project-id project-id :group-id group-id}
            :returning :id))

(defn delete-project-group! [project-id group-id]
  (q/delete :project-group {:project-id project-id :group-id group-id}))

(defn user-group-permission
  "Return the permissions for user-id in group-id"
  [user-id group-id]
  (q/find-one :user-group {:user-id user-id :group-id group-id}
              :permissions))

(defn group-projects
  "Return all projects group-id owns"
  [group-id & {:keys [private-projects?]}]
  (-> (q/find [:project :p] {:pg.group-id group-id} [:p.project-id :p.name :p.settings]
              :join [[:project-group :pg] :p.project-id])
      (cond->> (not private-projects?) (filter #(-> % :settings :public-access true?)))))

(defn create-group-stripe-customer!
  "Create a stripe customer for group-id owned by owner"
  [group-id owner]
  (let [{:keys [email]} owner
        group-name (group-id->name group-id)
        stripe-response (stripe/create-customer!
                         :description (str "Sysrev group name: " group-name)
                         :email email)
        stripe-customer-id (:id stripe-response)]
    (if-not (nil? stripe-customer-id)
      (try (q/modify :groups {:group-id group-id} {:stripe-id stripe-customer-id})
           {:success true}
           (catch Throwable e
             {:error {:message (str "Exception in " (util/current-function-name))
                      :exception e}}))
      {:error {:message
               (str "No customer id returned by stripe.com for group-id: " group-id)}})))

(defn group-id-from-url-id [url-id]
  ;; TODO: implement url-id strings for groups
  (util/parse-integer url-id))

(defn search-groups
  "Return groups whose name matches search term q"
  [q & {:keys [limit] :or {limit 5}}]
  (with-transaction
    (let [user-ids (db/raw-query
                    [(str "SELECT group_id,group_name FROM groups "
                          "WHERE (group_name ilike ?) AND group_name != 'public-reviewer' "
                          "ORDER BY group_name "
                          "LIMIT ?")
                     (str "%" q "%")
                     limit])]
      ;; check to see if we have results before returning the public info
      user-ids)))

(defn get-share-hash [org-id]
  (enc/encrypt-wrap64 {:type "org-invite-hash"
                       :org-id org-id}))

