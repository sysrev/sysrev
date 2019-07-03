(ns sysrev.db.plans
  (:require [honeysql.helpers :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.db.core :as db :refer [do-query do-execute with-transaction]]
            [sysrev.util :as util]))

(defn add-user-to-plan! [user-id plan sub-id]
  (with-transaction
    (let [now-epoch (util/to-epoch (db/sql-now))]
      (-> (insert-into :plan-user)
          (values [{:user-id user-id, :plan plan, :created now-epoch, :sub-id sub-id}])
          do-execute))))

(defn add-group-to-plan! [group-id plan sub-id]
  (with-transaction
    (let [now-epoch (util/to-epoch (db/sql-now))]
      (-> (insert-into :plan-group)
          (values [{:group-id group-id, :plan plan, :created now-epoch, :sub-id sub-id}])
          do-execute))))

(defn get-current-plan
  "Get the plan for which user is currently subscribed"
  [user-id]
  (-> (select :pu.* :sp.name)
      (from [:plan-user :pu])
      (join [:stripe-plan :sp] [:= :pu.plan :sp.id])
      (where [:= :pu.user-id user-id])
      do-query (->> (sort-by :created) last)))

(defn get-current-plan-group
  "Get the information for the plan the group is currently subscribed to"
  [group-id]
  (-> (select :pg.* :sp.name)
      (from [:plan-group :pg])
      (join [:stripe-plan :sp] [:= :pg.plan :sp.id])
      (where [:= :pg.group-id group-id])
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
  (-> (select :pss.*)
      (from [:project-support-subscriptions :pss])
      (join [:project :p] [:= :p.project-id :pss.project-id])
      (where [:and [:= :pss.user-id user-id] [:= :pss.status "active"]])
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
