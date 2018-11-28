(ns sysrev.db.funds
  (:require [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute to-jsonb sql-now]]
            [sysrev.util :as util]))

(def transaction-source-descriptor {:paypal-payment "PayPal/payment-id"
                                    :stripe-charge "Stripe/charge-id"
                                    :paypal-payout "PayPal/payout-batch-id"
                                    :sysrev-admin-fee "SysRev/admin-fee"})

(defn create-project-fund-entry!
  [{:keys [project-id user-id amount transaction-id transaction-source created]}]
  (-> (insert-into :project-fund)
      (values [{:project-id project-id
                :user-id user-id
                :amount amount
                :transaction-id transaction-id
                :transaction-source transaction-source
                :created created}])
      do-execute))

(defn project-funds
  [project-id]
  (-> (select :*)
      (from :project-fund)
      (where [:= :project-id project-id])
      do-query
      (->> (map :amount)
           (apply +))))

(defn create-project-fund-pending-entry!
  [{:keys [project-id user-id amount transaction-id transaction-source status created]}]
  (-> (insert-into :project-fund-pending)
      (values [{:project-id project-id
                :user-id user-id
                :amount amount
                :transaction-id transaction-id
                :transaction-source transaction-source
                :status status
                :created created}])
      do-execute))

(defn pending-funds
  [project-id]
    (-> (select :*)
      (from :project-fund-pending)
      (where [:and
              [:= :project-id project-id]
              [:not= :status "completed"]])
      do-query))

(defn update-project-fund-pending-entry!
  [{:keys [transaction-id status]}]
  (-> (sqlh/update :project-fund-pending)
      (sset {:status status
             :updated (util/now-unix-seconds)})
      (where [:= :transaction_id transaction-id])
      do-execute))

(defn get-pending-payment
  [payment-id]
    (-> (select :*)
      (from :project-fund-pending)
      (where [:and
              [:= :transaction_id payment-id]])
      do-query
      first))
