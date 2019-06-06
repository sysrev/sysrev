(ns sysrev.stripe
  (:require [clj-http.client :as http]
            [clj-stripe.cards :as cards]
            [clj-stripe.charges :as charges]
            [clj-stripe.common :as common]
            [clj-stripe.customers :as customers]
            [clj-stripe.plans :as plans]
            [clj-stripe.subscriptions :as subscriptions]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [sysrev.config.core :refer [env]]
            [sysrev.db.funds :as funds]
            [sysrev.db.plans :as db-plans]
            [sysrev.db.project :as project]
            [sysrev.util :refer [current-function-name]]))

(def stripe-secret-key (env :stripe-secret-key))

(def stripe-public-key (env :stripe-public-key))

;; https://dashboard.stripe.com/account/applications/settings
(def stripe-client-id (env :stripe-client-id))

(def stripe-url "https://api.stripe.com/v1")

(def default-plan "Basic")

(def default-req {:basic-auth stripe-secret-key
                  :coerce :always
                  :throw-exceptions false
                  :as :json})

(defn execute-action
  [action]
  (common/with-token stripe-secret-key
    (common/execute action)))

(defn create-customer!
  "Create a stripe customer"
  [& {:keys [email description]}]
  (execute-action
   (customers/create-customer
    (when email
      (customers/email email))
    (when description
      (common/description description)))))

(defn get-customer
  "Get customer associated with stripe customer id"
  [stripe-id]
  (execute-action
   (customers/get-customer stripe-id)))

(defn read-customer-sources
  "Return the customer fund sources"
  [stripe-id]
  (let [customer (get-customer stripe-id)
        sources (:sources customer)
        default-source (:default_source customer)]
    (assoc sources :default-source default-source)))

(defn read-default-customer-source
  [stripe-id]
  (let [customer-sources (read-customer-sources stripe-id)
        default-source (:default-source customer-sources)]
    (first (filter #(= (:id %) default-source) (:data customer-sources)))))

(defn update-customer-card!
  "Update a stripe customer with a stripe-token returned by stripe.js"
  [stripe-id stripe-token]
  (execute-action
   (customers/update-customer
    stripe-id
    (common/card (get-in stripe-token ["token" "id"])))))

;; used for testing
(defn delete-customer-card!
  "Delete a card from a customer"
  [stripe-id source-id]
  (http/delete (str stripe-url "/customers/" stripe-id "/sources/" source-id)
               default-req))

(defn delete-customer!
  "Delete stripe customer entry for user"
  [user]
  (if-not (nil? (:stripe-id user))
    (execute-action (customers/delete-customer (:stripe-id user)))
    (log/info (str "Error in " (current-function-name) ": " "no stripe-id associated with user (user-id: " (:user-id user) ")"))))

;; !!! WARNING !!!
;; !! This is only a util for dev environemnt !!
;; !! DO NOT RUN IN PRODUCTION !!
;;
;; Beware, you could delete all of our customers if you are using
;; a production stripe-secret-key in your profiles.clj, which you should
;; absolutely NEVER DO IN THE FIRST PLACE!!!!
;;
;; Because of the sensitive nature of this fn, it is hardcoded to only use a 
;; key extracted from profiles.clj
;;
;;
;; !!! WARNING !!
(defn- delete-all-customers!
  []
  (if (and (re-matches #"pk_test_.*" (env :stripe-public-key))
           (re-matches #"sk_test_.*" (env :stripe-secret-key)))
    (let [do-action (fn [action]
                      (common/with-token (env :stripe-secret-key)
                        (common/execute action)))]
      (mapv #(do-action (customers/delete-customer %))
            ;; note that even the limit-count is hard coded here
            ;; you may have to run this function more than once if you have
            ;; created a lot of stray stripe customers
            (->> (do-action (customers/get-customers (common/limit-count 100)))
                 :data
                 (mapv :id))))
    (log/info (str "Error in " (current-function-name) ": " "attempt to run with non-test keys"))))

(defn- delete-all-subscriptions!
  []
  (if (and (re-matches #"pk_test_.*" (env :stripe-public-key))
           (re-matches #"sk_test_.*" (env :stripe-secret-key)))
    (let [do-action (fn [action]
                      (common/with-token (env :stripe-secret-key)
                        (common/execute action)))]
      (mapv #(do-action (customers/delete-customer %))
            ;; note that even the limit-count is hard coded here
            ;; you may have to run this function more than once if you have
            ;; created a lot of stray stripe customers
            (->> (do-action (customers/get-customers (common/limit-count 100)))
                 :data
                 (mapv :id))))
    (log/info (str "Error in " (current-function-name) ": " "attempt to run with non-test keys"))))

(defn get-plans
  "Get all site plans"
  []
  (execute-action (plans/get-all-plans)))

(defn get-plan-id
  "Given a plan nickname, return the plan-id"
  [nickname]
  (->
   (filter #(= (:nickname %) nickname) (:data (get-plans)))
   first :id))


;; prorating does occur, but only on renewal day (e.g. day payment is due)
;; it is not prorated at the time of upgrade/downgrade
;; https://stripe.com/docs/subscriptions/upgrading-downgrading
(defn create-subscription-user!
  "Create a subscription using the basic plan for user. This subscription is used for all subsequent subscription plans. Return the stripe response."
  [user]
  (let [{:keys [start id plan] :as stripe-response}
        (execute-action (subscriptions/subscribe-customer
                         (common/plan (get-plan-id default-plan) )
                         (common/customer (:stripe-id user))
                         (subscriptions/do-not-prorate)))]
    (cond (:error stripe-response)
          stripe-response
          start
          (do (db-plans/add-user-to-plan! {:user-id (:user-id user)
                                           :plan (:id plan)
                                           :created start
                                           :sub-id id})
              stripe-response))))

(defn subscripe-customer!
  [stripe-id plan-id])

(defn create-subscription-org!
  "Create a subscription using the basic plan for group-id. This subscription is used for all subsequent subscription plans. Return the stripe response."
  [group-id stripe-id]
  (let [{:keys [start id plan] :as stripe-response}
        (execute-action (subscriptions/subscribe-customer
                         (common/plan (get-plan-id default-plan))
                         (common/customer stripe-id)
                         (subscriptions/do-not-prorate)))]
    (cond (:error stripe-response)
          stripe-response
          start
          (do (db-plans/add-group-to-plan! {:group-id group-id
                                            :plan (:id plan)
                                            :created start
                                            :sub-id id})
              stripe-response))))

;; not needed for main application, needed only in tests
(defn unsubscribe-customer!
  "Unsubscribe a user. This just removes user from all plans"
  [stripe-id]
  ;; need to remove the user from plan_user table!!!
  (execute-action (subscriptions/unsubscribe-customer
                   (common/customer stripe-id))))
;; https://stripe.com/docs/billing/subscriptions/quantities#setting-quantities

(defn support-project-monthly!
  "User supports a project-id for amount (integer cents). Does not handle overhead of increasing / decreasing already supported projects"
  [user project-id amount plan-name]
  (let [stripe-response
        (http/post (str stripe-url "/subscriptions")
                   (assoc default-req
                          :form-params
                          {"customer" (:stripe-id user)
                           "items[0][plan]" (:id (db-plans/get-support-project-plan))
                           "items[0][quantity]" amount
                           "metadata[project-id]" project-id}))]
    (cond ;; there was some kind of error
      (not= (:status stripe-response)
            200)
      {:status (:status stripe-response)
       :body (-> stripe-response
                 :body
                 (json/read-str :key-fn keyword))}
      :else
      ;; everything seems to be fine, let's update our records
      (let [{:keys [id created customer quantity status] :as body}
            (-> stripe-response
                :body
                (json/read-str :key-fn keyword))]
        (db-plans/upsert-support!
         {:id id
          :project-id project-id
          :user-id (:user-id user)
          :stripe-id customer
          :quantity quantity
          :status status
          :created created})
        body))))

;;https://stripe.com/docs/api/subscriptions/create
#_(defn subscribe-plan!
  "Subscribe to plan-name with stripe-id in quantity "
  [{:keys [stripe-id plan-id quantity]
    :or {quantity 1}}]
  (http/post (str stripe-url "/subscriptions")
             {:basic-auth stripe-secret-key
              :coerce :always
              :throw-exceptions false
              :as :json
              :form-params
              {"customer" stripe-id
               "items[0][plan]" plan-id
               "items[0][quantity]" quantity}}))

(defn get-subscription
  [sub-id]
  (http/get (str stripe-url "/subscriptions/" sub-id)
            default-req))

(defn get-subscription-item
  [sub-id]
  "Given a sub-id, get the first subscription item"
  (let [{:keys [body]} (get-subscription sub-id)]
    (when-not (:error body)
      (-> body :items :data first :id))))

(defn update-subscription-item!
  "Update subscription item with id with optional quantity and optional plan (plan-id)"
  [{:keys [id plan quantity]}]
  (http/post (str stripe-url "/subscription_items/" id)
             (assoc default-req
                    :form-params
                    (cond-> {}
                      quantity (assoc "quantity" quantity)
                      plan (assoc "plan" plan)))))

(defn cancel-subscription!
  "Cancels subscription id"
  [{:keys [subscription-id]}]
  (let [{:keys [project-id user-id]}
        (db-plans/support-subscription subscription-id)
        stripe-response
        (http/delete (str stripe-url "/subscriptions/" subscription-id)
                     default-req)]
    (cond ;; there is an error, report it
      (= (:status stripe-response) 404)
      (-> stripe-response
          :body
          (json/read-str :key-fn keyword)
          :error)
      ;; everything is ok, cancel this subscription
      (= (:status stripe-response) 200)
      (let [{:keys [id created customer quantity status]}
            (-> stripe-response
                :body
                (json/read-str :key-fn keyword))]
        (db-plans/upsert-support!
         {:id id
          :project-id project-id
          :user-id user-id
          :stripe-id customer
          :quantity quantity
          :status status
          :created created})))))

(defn cancel-subscription!
  [{:keys [subscription-id]}]
  (http/delete (str stripe-url "/subscriptions/" subscription-id)
               default-req))

(defn support-project-once!
  "Make a one-time contribution to a project for amount"
  [user project-id amount]
  (try
    (let [{:keys [id created] :as stripe-response}
          (execute-action (merge (charges/create-charge
                                  (common/money-quantity amount "usd")
                                  (common/description "one time")
                                  (common/customer (:stripe-id user)))
                                 {"metadata" {"project-id" project-id}}))]
      (if-not (nil? (and id created))
        ;; charge was a success, insert into the db
        (do (funds/create-project-fund-entry! {:project-id project-id
                                               :user-id (:user-id user)
                                               :transaction-id id
                                               :transaction-source (:stripe-charge funds/transaction-source-descriptor)
                                               :amount amount
                                               :created created})
            {:success true})
        ;; charge was not success, return response
        stripe-response))
    (catch Throwable e
      {:error {:message (.getMessage e)}})))

;;https://stripe.com/docs/connect/quickstart?code=ac_Dqv4kl5grW1R7eRtNRQHahcuO6TGcQIq&state=state#express-account-creation
(defn finalize-stripe-user!
  "finalize the stripe user on stripe, return the stripe customer-id"
  [stripe-code]
  (http/post "https://connect.stripe.com/oauth/token"
             (assoc default-req
                    :form-params
                    {"client_secret" stripe-secret-key
                     "code" stripe-code
                     "grant_type" "authorization_code"})))

;; https://stripe.com/docs/api/transfers/create
(defn pay-stripe-user!
  [stripe-account-id amount]
  (http/post (str stripe-url "/transfers")
             (assoc default-req
                    :form-params {"amount" amount
                                  "currency" "usd"
                                  "destination" stripe-account-id} )))
;;https://stripe.com/docs/api/balance/balance_retrieve
(defn current-balance
  []
  (http/get (str stripe-url "/balance")
            default-req))

;;; charge for payments: (- 1000 (* (/ 2.9 100) 1000) 30)

;; https://stripe.com/docs/api/transfers/retrieve
(defn retrieve-transfer
  [transfer-id]
  (http/get (str stripe-url "/transfers/" transfer-id)
            default-req))

(defn retrieve-charge
  [charge-id]
  (http/get (str stripe-url "/charges/" charge-id)
            default-req))

(defn transaction-history
  "Given a charge-id (ch_*), return the balance history"
  [charge-id]
  (let [{:keys [body]} (retrieve-charge charge-id)
        balance-transaction (:balance_transaction body)]
    (http/get (str stripe-url "/balance/history/" balance-transaction)
              default-req)))
