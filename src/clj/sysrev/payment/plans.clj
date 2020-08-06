(ns sysrev.payment.plans
  (:require [honeysql.helpers :refer [join]]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.db.queries :as q]))

(defn add-user-to-plan! [user-id plan sub-id]
  (q/create :plan-user {:user-id user-id :plan plan :sub-id sub-id}))

(defn add-group-to-plan! [group-id plan sub-id]
  (q/create :plan-group {:group-id group-id :plan plan :sub-id sub-id}))

(defn user-current-plan
  "Get the plan for which user is currently subscribed"
  [user-id]
  (first (q/find [:plan-user :pu] {:user-id user-id} [:pu.* :sp.name]
                 :join [[:stripe-plan :sp] [:= :pu.plan :sp.id]]
                 :order-by [:pu.created :desc], :limit 1)))

(defn group-current-plan
  "Get the plan for which group is currently subscribed"
  [group-id]
  (first (q/find [:plan-group :pg] {:group-id group-id} [:pg.* :sp.name]
                 :join [[:stripe-plan :sp] [:= :pg.plan :sp.id]]
                 :order-by [:pg.created :desc], :limit 1)))

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
