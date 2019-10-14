(ns sysrev.payment.stripe
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
            [sysrev.db.queries :as q]
            [sysrev.project.funds :as funds]
            [sysrev.payment.plans :as db-plans]
            [sysrev.project.core :as project]
            [sysrev.util :as util :refer [current-function-name]]))

;; https://dashboard.stripe.com/account/applications/settings
(def stripe-secret-key (env :stripe-secret-key))
(def stripe-public-key (env :stripe-public-key))
(def stripe-client-id (env :stripe-client-id))
(def stripe-url "https://api.stripe.com/v1")
(def default-plan "Basic")

(def default-req {:basic-auth stripe-secret-key
                  :coerce :always
                  :throw-exceptions false
                  :as :json})

(defn execute-action [action]
  (common/with-token stripe-secret-key
    (common/execute action)))

(defn create-customer! [& {:keys [email description]}]
  (execute-action (customers/create-customer
                   (some-> email (customers/email))
                   (some-> description (common/description)))))

(defn get-customer [stripe-id]
  (execute-action (customers/get-customer stripe-id)))

(defn read-customer-sources
  "Return the customer fund sources"
  [stripe-id]
  (let [{:keys [sources default_source]} (get-customer stripe-id)]
    (assoc sources :default-source default_source)))

(defn read-default-customer-source [stripe-id]
  (let [{:keys [data default-source] :as sources} (read-customer-sources stripe-id)]
    (first (->> data (filter #(= default-source (:id %)))))))

(defn update-customer-card!
  "Update a stripe customer with a stripe-token returned by stripe.js"
  [stripe-id stripe-token]
  (execute-action (customers/update-customer
                   stripe-id (common/card (get-in stripe-token ["token" "id"])))))

;; used for testing
(defn delete-customer-card! [stripe-id source-id]
  (http/delete (str stripe-url "/customers/" stripe-id "/sources/" source-id) default-req))

(defn delete-customer! [{:keys [stripe-id user-id]}]
  (if stripe-id
    (execute-action (customers/delete-customer stripe-id))
    (log/warnf "Error in %s: no stripe-id associated with user=%d"
               (current-function-name) user-id)))

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
(defn- delete-all-customers! []
  (if (and (re-matches #"pk_test_.*" (env :stripe-public-key))
           (re-matches #"sk_test_.*" (env :stripe-secret-key)))
    (let [do-action (fn [action]
                      (common/with-token (env :stripe-secret-key)
                        (common/execute action)))]
      (mapv #(do-action (customers/delete-customer %))
            ;; note that even the limit-count is hard coded here
            ;; you may have to run this function more than once if you have
            ;; created a lot of stray stripe customers
            (mapv :id (:data (do-action (customers/get-customers (common/limit-count 100)))))))
    (log/info "Error in" (current-function-name) "- attempt to run with non-test keys")))

(defn- delete-all-subscriptions! []
  (if (and (re-matches #"pk_test_.*" (env :stripe-public-key))
           (re-matches #"sk_test_.*" (env :stripe-secret-key)))
    (let [do-action (fn [action]
                      (common/with-token (env :stripe-secret-key)
                        (common/execute action)))]
      (mapv #(do-action (customers/delete-customer %))
            ;; note that even the limit-count is hard coded here
            ;; you may have to run this function more than once if you have
            ;; created a lot of stray stripe customers
            (mapv :id (:data (do-action (customers/get-customers (common/limit-count 100)))))))
    (log/info "Error in" (current-function-name) "- attempt to run with non-test keys")))

(defn all-plans []
  (execute-action (plans/get-all-plans)))

(defn get-plan-id [nickname]
  (:id (first (->> (:data (all-plans))
                   (filter #(= nickname (:nickname %)))))))

;; prorating does occur, but only on renewal day (e.g. day payment is due)
;; it is not prorated at the time of upgrade/downgrade
;; https://stripe.com/docs/subscriptions/upgrading-downgrading
(defn create-user-subscription!
  "Create a subscription using the basic plan for user. This
  subscription is used for all subsequent subscription plans. Return
  the stripe response."
  [{:keys [user-id stripe-id]}]
  (let [{:keys [start plan error] :as response}
        (execute-action (subscriptions/subscribe-customer
                         (common/plan (get-plan-id default-plan))
                         (common/customer stripe-id)
                         (subscriptions/do-not-prorate)))]
    (when-not error
      (db-plans/add-user-to-plan! user-id (:id plan) (:id response)))
    response))

(defn create-org-subscription!
  "Create a subscription using the basic plan for group-id. This
  subscription is used for all subsequent subscription plans. Return
  the stripe response."
  [group-id stripe-id]
  (let [{:keys [start plan error] :as response}
        (execute-action (subscriptions/subscribe-customer
                         (common/plan (get-plan-id default-plan))
                         (common/customer stripe-id)
                         (subscriptions/do-not-prorate)))]
    (when-not error
      (db-plans/add-group-to-plan! group-id (:id plan) (:id response)))
    response))

;; not needed for main application, needed only in tests
(defn ^:unused unsubscribe-customer!
  "Removes a customer from all plans."
  [stripe-id]
  ;; need to remove the user from plan_user table!!!
  (execute-action (subscriptions/unsubscribe-customer
                   (common/customer stripe-id))))
;; https://stripe.com/docs/billing/subscriptions/quantities#setting-quantities

(defn support-project-monthly!
  "User supports a project-id for amount (integer cents). Does not
  handle overhead of increasing / decreasing already supported
  projects"
  [{:keys [user-id stripe-id]} project-id amount plan-name]
  (let [{:keys [body status] :as stripe-response}
        (http/post (str stripe-url "/subscriptions")
                   (assoc default-req
                          :form-params
                          {"customer" stripe-id
                           "items[0][plan]" (:id (db-plans/stripe-support-project-plan))
                           "items[0][quantity]" amount
                           "metadata[project-id]" project-id}))]
    (if (= status 200) ; success
      (let [{:keys [id created customer quantity status]}
            (json/read-str body :key-fn keyword)]
        (db-plans/upsert-support! {:id id :project-id project-id :user-id user-id
                                   :stripe-id customer :quantity quantity :status status
                                   :created (some-> created util/to-clj-time)})
        body)
      ;; unknown error
      {:status status :body (json/read-str body :key-fn keyword)})))

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

(defn get-subscription [sub-id]
  (http/get (str stripe-url "/subscriptions/" sub-id) default-req))

(defn get-subscription-item
  "Given a sub-id, get the first subscription item"
  [sub-id]
  (let [{:keys [body]} (get-subscription sub-id)]
    (when-not (:error body)
      (-> body :items :data first :id))))

(defn update-subscription-item!
  "Update subscription item with id with optional quantity and optional plan (plan-id)"
  [{:keys [id plan quantity]}]
  (http/post (str stripe-url "/subscription_items/" id)
             (assoc default-req :form-params (cond-> {}
                                               quantity (assoc "quantity" quantity)
                                               plan (assoc "plan" plan)))))

(defn cancel-subscription! [{:keys [sub-id]}]
  (let [{:keys [project-id user-id]} (db-plans/lookup-support-subscription sub-id)
        {:keys [status body]} (http/delete (str stripe-url "/subscriptions/" sub-id) default-req)]
    (cond ;; there is an error, report it
      (= status 404) (:error (json/read-str body :key-fn keyword))
      ;; everything is ok, cancel this subscription
      (= status 200)
      (let [{:keys [id created customer quantity status]}
            (json/read-str body :key-fn keyword)]
        (db-plans/upsert-support! {:id id :project-id project-id :user-id user-id
                                   :stripe-id customer :quantity quantity :status status
                                   :created (some-> created util/to-clj-time)})))))

(defn cancel-subscription! [{:keys [subscription-id]}]
  (http/delete (str stripe-url "/subscriptions/" subscription-id) default-req))

(defn support-project-once! [{:keys [stripe-id user-id]} project-id amount]
  (let [transaction-source (:stripe-charge funds/transaction-source-descriptor)
        {:keys [id created] :as stripe-response}
        (execute-action (merge (charges/create-charge (common/money-quantity amount "usd")
                                                      (common/description "one time")
                                                      (common/customer stripe-id))
                               {"metadata" {"project-id" project-id}}))]
    (if (and id created)
      ;; charge was a success, insert into the db
      (do (funds/create-project-fund-entry!
           {:project-id project-id :user-id user-id :amount amount
            :transaction-id id :transaction-source transaction-source
            :created (some-> created util/to-clj-time)})
          {:success true})
      ;; charge was not success, return response
      stripe-response)))

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
             (assoc default-req :form-params {"amount" amount
                                              "currency" "usd"
                                              "destination" stripe-account-id} )))

;;https://stripe.com/docs/api/balance/balance_retrieve
(defn current-balance []
  (http/get (str stripe-url "/balance") default-req))

;;; charge for payments: (- 1000 (* (/ 2.9 100) 1000) 30)

;; https://stripe.com/docs/api/transfers/retrieve
(defn retrieve-transfer [transfer-id]
  (http/get (str stripe-url "/transfers/" transfer-id) default-req))

(defn retrieve-charge [charge-id]
  (http/get (str stripe-url "/charges/" charge-id) default-req))

(defn transaction-history
  "Given a charge-id (ch_*), return the balance history"
  [charge-id]
  (let [{:keys [body]} (retrieve-charge charge-id)
        {:keys [balance_transaction]} body]
    (http/get (str stripe-url "/balance/history/" balance_transaction) default-req)))
