(ns sysrev.stripe
  (:require [clj-stripe.cards :as cards]
            [clj-stripe.charges :as charges]
            [clj-stripe.common :as common]
            [clj-stripe.customers :as customers]
            [clj-stripe.plans :as plans]
            [clj-stripe.subscriptions :as subscriptions]
            [environ.core :refer [env]]
            [sysrev.db.plans :as db-plans]))

(def stripe-secret-key (or (System/getProperty "STRIPE_SECRET_KEY")
                           (env :stripe-secret-key)))

(def stripe-public-key (or (System/getProperty "STRIPE_PUBLIC_KEY")
                           (env :stripe-public-key)))

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

(defn get-customer
  "Get customer associated with stripe customer id"
  [user]
  (execute-action
   (customers/get-customer (:stripe-id user))))

(defn update-customer-card!
  "Update a stripe customer with a stripe-token returned by stripe.js"
  [user stripe-token]
  (execute-action
   (customers/update-customer
    (:stripe-id user)
    (common/card (get-in stripe-token ["token" "id"])))))

(defn delete-customer-card!
  "Delete a card from a customer"
  [stripe-customer-id])

(defn delete-customer!
  "Delete user as a SysRev stripe customer"
  [user]
  (execute-action (customers/delete-customer (:stripe-id user))))

;; !!! WARNING !!!
;; !! This is only a util for dev environemnt !!
;; !! DO NOT RUN IN PRODUCTION !!
;;
;; Beware, you could delete all of our customers if you are using
;; a production stripe-secret-key in your profiles.clj, which you should
;; absolutely NEVER DO IN THE FIRST PLACE!!!!
;;
;; Because of the sensitive nature of this fn, it is hardcoded to only use the
;; test key.
;;
;; !!! WARNING !!!
(defn- delete-all-customers!
  []
  (let [do-action (fn [action]
                    (common/with-token (env :stripe-secret-key)
                      (common/execute action)))]
    (mapv #(do-action (customers/delete-customer %))
          ;; note that even the limit-count is hard coded here
          ;; you may have to run this function more than once if you have
          ;; created a lot of stray stripe customers
          (->> (do-action (customers/get-customers (common/limit-count 100)))
               :data
               (mapv :id)))))

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


;; prorating does occur, but only on renewal day (e.g. day payment is due)
;; it is not prorated at the time of upgrade/downgrade
;; https://stripe.com/docs/subscriptions/upgrading-downgrading
(defn subscribe-customer!
  "Subscribe SysRev user to plan-name. Return the stripe response. If the customer is subscribed to a paid plan and no payment method has been attached to the user, this will result in an error in the response"
  [user plan-name]
  (let [{:keys [created id] :as stripe-response}
        (execute-action (subscriptions/subscribe-customer
                         (common/plan (get-plan-id plan-name))
                         (common/customer (:stripe-id user))
                         (subscriptions/do-not-prorate)))]
    (cond (:error stripe-response)
          stripe-response
          created
          (do (db-plans/add-user-to-plan! {:user-id (:user-id user)
                                           :name plan-name
                                           :created created
                                           :sub-id id})
              stripe-response))))

(defn unsubscribe-customer!
  "Unsubscribe a user. This just removes user from all plans"
  [user]
  ;; need to remove the user from plan_user table!!!
  (execute-action (subscriptions/unsubscribe-customer
                   (common/customer (:stripe-id user)))))
