(ns sysrev.payment.plans
  (:require [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.plans-info :as plans-info]))

(defn add-user-to-plan! [user-id plan-id sub-id]
  (db/with-transaction
    (if (q/find :plan-user {:user-id user-id :sub-id sub-id})
      (q/modify :plan-user {:user-id user-id :sub-id sub-id} {:plan plan-id})
      (q/create :plan-user {:user-id user-id :plan plan-id :sub-id sub-id})))
  (db/clear-query-cache))

(defn add-group-to-plan! [group-id plan-id sub-id]
  (db/with-transaction
    (if (q/find :plan-group {:group-id group-id :sub-id sub-id})
      (q/modify :plan-group {:group-id group-id :sub-id sub-id} {:plan plan-id})
      (q/create :plan-group {:group-id group-id :plan plan-id :sub-id sub-id})))
  (db/clear-query-cache))

(defn user-current-plan
  "Get the plan for which user is currently subscribed"
  [user-id]
  (->> (q/find [:plan-user :pu] {:user-id user-id} [:pu.* :sp.nickname :sp.interval :sp.id :sp.amount :sp.tiers :sp.product-name]
               :join [[:stripe-plan :sp] [:= :pu.plan :sp.id]]
               :order-by [:pu.created :desc])
       (sort-by #(if (plans-info/pro? (:nickname %)) 0 1))
       first))

(defn group-current-plan
  "Get the plan for which group is currently subscribed"
  [group-id]
  (let [owner-id (first (q/find :user-group {:group-id group-id, "owner" :%any.permissions}
                                :user-id, :order-by :created, :limit 1))]
    (user-current-plan owner-id)))

(defn stripe-support-project-plan
  "Returns information for the stripe plan used to subscribe users to
  monthly support for a project."
  []
  (q/find-one :stripe-plan {:name "ProjectSupport"}))

(defn user-support-subscriptions
  "Returns all active support subscriptions for user."
  [{:keys [user-id] :as _user}]
  (q/find [:project-support-subscriptions :pss] {:user-id user-id :pss.status "active"}
          :pss.*, :join [[:project :p] :pss.project-id]))

(defn lookup-support-subscription [id]
  (q/find-one :project-support-subscriptions {:id id}))

(defn user-current-project-support [{:keys [user-id] :as _user} project-id]
  (q/find-one :project-support-subscriptions
              {:user-id user-id :project-id project-id :status "active"}))

(defn upsert-support!
  "Add a support entry for amount by user supporting project. Can also
  be used to change the status of the subscription."
  [{:keys [id project-id user-id stripe-id quantity status created] :as support-map}]
  (q/create :project-support-subscriptions support-map
            :prepare #(upsert % (-> (on-conflict :id)
                                    (do-update-set :status)))))
