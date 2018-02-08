(ns sysrev.stripe
  (:require [clj-stripe.cards :as cards]
            [clj-stripe.charges :as charges]
            [clj-stripe.common :as common]
            [clj-stripe.customers :as customers]
            [clj-stripe.plans :as plans]
            [clj-stripe.subscriptions :as subscriptions]
            [environ.core :refer [env]]
            [sysrev.db.plans :as db-plans]))

(def token (atom nil))

(def stripe-secret-key (or (env :stripe-secret-key)
                           (System/getProperty "STRIPE_SECRET_KEY")))
(def stripe-public-key (or (env :stripe-public-key)
                           (System/getProperty "STRIPE_PUBLIC_KEY")))

(defn execute-action
  [action]
  (common/with-token stripe-secret-key
    (common/execute action)))

(defn create-customer!
  "Create a stripe customer"
  [email uuid]
  (execute-action
   (customers/create-customer
    (customers/email email)
    (common/description (str "SysRev UUID: " uuid)))))

(defn update-customer-card!
  "Update a stripe customer with a stripe-token returned by stripe.js"
  [stripe-customer-id stripe-token]
  (execute-action
   (customers/update-customer
    stripe-customer-id
    (common/card (get-in stripe-token ["token" "id"])))))

(defn delete-customer-card!
  "Delete a card from a customer"
  [stripe-customer-id])

(defn delete-customer!
  "Delete user as a SysRev stripe customer"
  [user]
  (execute-action (customers/delete-customer (:stripe-id user))))

(defn get-plans
  "Get all plans that SysRev offers"
  []
  (execute-action (plans/get-all-plans)))

(defn get-plan-id
  "Given a plan name, return the plan-id"
  [plan-name]
  (->
   (filter #(= (:name %) plan-name) (:data (get-plans)))
   first :id))

(defn subscribe-customer!
  "Subscribe SysRev user to plan-name. Return the stripe response. If the customer is subscribed to a paid plan and no payment method has been attached to the user, this will result in an error in the response"
  [user plan-name]
  (let [stripe-response (execute-action (subscriptions/subscribe-customer
                                         (common/plan (get-plan-id plan-name))
                                         (common/customer (:stripe-id user))
                                         (subscriptions/do-not-prorate)))]
    (db-plans/add-user-to-plan! user plan-name)
    stripe-response))

(defn unsubscribe-customer!
  "Unsubscribe a customer. This just removes user from all plans"
  [stripe-customer-id]
  (execute-action (subscriptions/unsubscribe-customer
                   (common/customer stripe-customer-id))))
