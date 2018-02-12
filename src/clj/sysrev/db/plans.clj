(ns sysrev.db.plans
  (:require [honeysql.helpers :refer [values from select where insert-into]]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.db.core :refer [do-query do-execute]]))

(defn add-user-to-plan!
  "Add user-id to plan with name at time created with subscription id sub-id"
  [{:keys [user-id name created sub-id]}]
  (let [product (-> (select :product)
                    (from :stripe-plan)
                    (where [:= :name name])
                    do-query
                    first
                    :product)]
    (-> (insert-into :plan-user)
        (values [{:user-id user-id
                  :product product
                  :created created
                  :sub-id sub-id}])
        do-execute)))

(defn get-user-plan
  "Get the users current plan"
  [user]
  (let [{:keys [user-id]} user
        product (-> (select :product)
                    (from :plan-user)
                    (where [:= :user-id user-id])
                    do-query first :product)]
    (-> (select :name)
        (from :stripe-plan)
        (where [:= :product product])
        do-query
        first
        :name)))
