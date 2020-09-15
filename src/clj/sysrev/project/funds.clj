(ns sysrev.project.funds
  (:require [sysrev.db.queries :as q]
            [sysrev.util :as util :refer [sum]]))

(def transaction-source-descriptor {:paypal-payment "PayPal/payment-id"
                                    :stripe-charge "Stripe/charge-id"
                                    :paypal-payout "PayPal/payout-batch-id"
                                    :sysrev-admin-fee "SysRev/admin-fee"
                                    :paypal-order "PayPal/order-id"})

(defn create-project-fund-entry!
  [{:keys [project-id user-id amount transaction-id transaction-source created] :as fields}]
  (q/create :project-fund fields))

(defn project-funds [project-id]
  (sum (q/find :project-fund {:project-id project-id} :amount)))

(defn ^:unused create-project-fund-pending-entry!
  [{:keys [project-id user-id amount transaction-id transaction-source status created]
    :as fields}]
  (q/create :project-fund-pending fields))

(defn pending-funds [project-id]
  (q/find :project-fund-pending {:project-id project-id} :*
          :where [:!= :status "completed"]))

(defn update-project-fund-pending-entry! [{:keys [transaction-id status]}]
  (q/modify :project-fund-pending {:transaction-id transaction-id}
            {:status status :updated :%now}))

(defn get-pending-payment [payment-id]
  (q/find-one :project-fund-pending {:transaction-id payment-id}))
