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
    (common/description (str "Sysrev UUID: " uuid)))))

(defn get-customer
  "Get customer associated with stripe customer id"
  [user]
  (execute-action
   (customers/get-customer (:stripe-id user))))

(defn read-customer-sources
  "Return the customer fund sources"
  [user]
  (let [customer (get-customer user)
        sources (:sources customer)
        default-source (:default_source customer)]
    (assoc sources :default-source default-source)))

(defn read-default-customer-source
  [user]
  (let [customer-sources (read-customer-sources user)
        default-source (:default-source customer-sources)]
    (first (filter #(= (:id %) default-source) (:data customer-sources)))))

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
;; !!! WARNING !!!
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

(defn get-plans
  "Get all site plans"
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
  "Subscribe user to plan-name. Return the stripe response. If the customer is subscribed to a paid plan and no payment method has been attached to the user, this will result in an error in the response. on-success is a fn called when transaction is succesfully completed on Stripe"
  [user plan-name & {:keys [on-success]
                     :or {on-success (fn [user plan-name created id]
                                       (db-plans/add-user-to-plan! {:user-id (:user-id user)
                                                                    :name plan-name
                                                                    :created created
                                                                    :sub-id id}))}}]
  (let [{:keys [created id] :as stripe-response}
        (execute-action (subscriptions/subscribe-customer
                         (common/plan (get-plan-id plan-name))
                         (common/customer (:stripe-id user))
                         (subscriptions/do-not-prorate)))]
    (cond (:error stripe-response)
          stripe-response
          created
          (do (on-success user plan-name created id)
              stripe-response))))

(defn unsubscribe-customer!
  "Unsubscribe a user. This just removes user from all plans"
  [user]
  ;; need to remove the user from plan_user table!!!
  (execute-action (subscriptions/unsubscribe-customer
                   (common/customer (:stripe-id user)))))
;; https://stripe.com/docs/billing/subscriptions/quantities#setting-quantities

(defn support-project-monthly!
  "User supports a project-id for amount (integer cents). Does not handle overhead of increasing / decreasing already supported projects"
  [user project-id amount]
  (let [stripe-response
        (http/post (str stripe-url "/subscriptions")
                   {:basic-auth stripe-secret-key
                    :throw-exceptions false
                    :form-params
                    {"customer" (:stripe-id user)
                     "items[0][plan]" (:id
                                       (db-plans/get-support-project-plan))
                     "items[0][quantity]" amount
                     "metadata[project-id]" project-id}})]
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

(defn cancel-subscription!
  "Cancels subscription id"
  [subscription-id]
  (let [{:keys [project-id user-id]}
        (db-plans/support-subscription subscription-id)
        stripe-response
        (http/delete (str stripe-url "/subscriptions/" subscription-id)
                     {:basic-auth stripe-secret-key
                      :throw-exceptions false})]
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
             {:form-params {"client_secret" stripe-secret-key
                            "code" stripe-code
                            "grant_type" "authorization_code"}
              :throw-exceptions false
              :as :json
              :coerce :always}))

;; https://stripe.com/docs/api/transfers/create
(defn pay-stripe-user!
  [stripe-account-id amount]
  (http/post (str stripe-url "/transfers")
             {:basic-auth stripe-secret-key
              :throw-exceptions false
              :as :json
              :coerce :always
              :form-params {"amount" amount
                            "currency" "usd"
                            "destination" stripe-account-id}}))
;;https://stripe.com/docs/api/balance/balance_retrieve
(defn current-balance
  []
  (http/get (str stripe-url "/balance")
            {:basic-auth stripe-secret-key
             :throw-exceptions false
             :as :json
             :coerce :always}))

;;; charge for payments: (- 1000 (* (/ 2.9 100) 1000) 30)

;; https://stripe.com/docs/api/transfers/retrieve
(defn retrieve-transfer
  [transfer-id]
  (http/get (str stripe-url "/transfers/" transfer-id)
            {:basic-auth stripe-secret-key
             :throw-excetions false
             :as :json
             :coerce :always}))

(defn retrieve-charge
  [charge-id]
  (http/get (str stripe-url "/charges/" charge-id)
            {:basic-auth stripe-secret-key
             :throw-exceptions false
             :as :json
             :coerce :always}))

(defn transaction-history
  "Given a charge-id (ch_*), return the balance history"
  [charge-id]
  (let [{:keys [body]} (retrieve-charge charge-id)
        balance-transaction (:balance_transaction body)]
    (http/get (str stripe-url "/balance/history/" balance-transaction)
              {:basic-auth stripe-secret-key
               :throw-exceptions false
               :as :json
               :coerce :always})))
