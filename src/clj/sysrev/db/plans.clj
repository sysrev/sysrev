(ns sysrev.db.plans
  (:require [honeysql.helpers :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.db.core :refer [do-query do-execute]]))

(defn add-user-to-plan!
  "Add user-id to plan with at time created with subscription id sub-id"
  [{:keys [user-id plan created sub-id]}]
  (-> (insert-into :plan-user)
      (values [{:user-id user-id, :plan plan, :created created, :sub-id sub-id}])
      do-execute))

(defn add-group-to-plan!
  "Add a group-id to plan with name at time created with subscription id sub-id"
  [{:keys [group-id plan created sub-id]}]
  (-> (insert-into :plan-group)
      (values [{:group-id group-id, :plan plan, :created created, :sub-id sub-id}])
      do-execute))

(defn get-current-plan
  "Get the plan for which user is currently subscribed"
  [user-id]
  (-> (select :*)
      (from [:plan-user :pu])
      (join [:stripe-plan :sp] [:= :pu.plan :sp.id])
      (where [:= :user-id user-id])
      do-query (->> (sort-by :created) last)))

(defn get-current-plan-group
  "Get the information for the plan the group is currently subscribed to"
  [group-id]
  (-> (select :*)
      (from [:plan-group :pg])
      (join [:stripe-plan :sp] [:= :pg.plan :sp.id])
      (where [:= :group-id group-id])
      do-query (->> (sort-by :created) last)))

(defn get-support-project-plan
  "Return the information for the support project plan used to subscribe
  users to a monthly support."
  []
  (-> (select :*)
      (from :stripe-plan)
      (where [:= :name "ProjectSupport"])
      do-query first))

(defn user-support-subscriptions
  "Return all support subscriptions for user which are active"
  [{:keys [user-id] :as user}]
  (-> (select :*)
      (from [:project-support-subscriptions :pss])
      (join [:project :p] [:= :p.project-id :pss.project-id])
      (where [:and [:= :user-id user-id] [:= :status "active"]])
      do-query))

(defn support-subscription
  "Return the subscription info for id"
  [id]
  (-> (select :*)
      (from :project-support-subscriptions)
      (where [:= :id id])
      do-query first))

(defn user-current-project-support
  "Given a project-id and a user, return the corresponding active subscription"
  [{:keys [user-id] :as user} project-id]
  (-> (select :*)
      (from :project-support-subscriptions)
      (where [:and [:= :user-id user-id] [:= :project-id project-id] [:= :status "active"]])
      do-query first))

(defn upsert-support!
  "Add a support entry for amount by user supporting project. Can also
  be used to change the status of the subscription."
  [{:keys [id project-id user-id stripe-id quantity status created] :as support-object}]
  (-> (insert-into :project-support-subscriptions)
      (values [support-object])
      (upsert (-> (on-conflict :id)
                  (do-update-set :status)))
      do-execute))
