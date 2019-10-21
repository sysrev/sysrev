(ns sysrev.payment.stripe
  (:require [clj-http.client :as http]
            [clj-stripe.charges :as charges]
            [clj-stripe.common :as common]
            [clj-stripe.customers :as customers]
            [clj-stripe.plans :as plans]
            [clj-stripe.subscriptions :as subscriptions]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [sysrev.config.core :refer [env]]
            [sysrev.project.funds :as funds]
            [sysrev.payment.plans :as db-plans]
            [sysrev.util :as util :refer [current-function-name]]))

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

(defn execute-action [action]
  (common/with-token stripe-secret-key
    (common/execute action)))

(defn stripe-get [uri & [query-params]]
  (:body (http/get (str stripe-url uri)
                   (cond-> default-req
                     query-params
                     (assoc :query-params (clojure.walk/stringify-keys query-params))))))

(defn stripe-post [uri & [form-params]]
  (:body (http/post (str stripe-url uri)
                    (assoc default-req
                           :form-params
                           (clojure.walk/stringify-keys form-params)))))

(defn stripe-delete [uri]
  (:body (http/delete (str stripe-url uri) default-req)))

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
  (let [{:keys [sources default_source]} (get-customer stripe-id)]
    (assoc sources :default-source default_source)))

(defn read-default-customer-source [stripe-id]
  (let [{:keys [data default-source] :as sources} (read-customer-sources stripe-id)]
    (first (->> data (filter #(= (:id %) default-source))))))

(defn get-customer-payment-methods [stripe-id]
  (stripe-get "/payment_methods" {:customer stripe-id :type "card"}))

(defn get-payment-method
  "Get the payment-method by its id "
  [id]
  (stripe-get (str "/payment_methods/" id)))

(defn get-customer-invoice-default-payment-method
  [stripe-id]
  (if-let [id (get-in (get-customer stripe-id)
                      [:invoice_settings :default_payment_method])]
    (get-payment-method id)))

(defn update-customer-payment-method!
  "Update a stripe customer with a payment-method id returned by stripe.js"
  [stripe-id payment-method]
  (let [response (http/post (str stripe-url "/payment_methods/" payment-method "/attach")
                            (assoc default-req
                                   :form-params {"customer"
                                                 stripe-id}))]
    (if (:error response)
      {:error (:error response)}
      (http/post (str stripe-url "/customers/" stripe-id)
                 (assoc default-req
                        :form-params {"invoice_settings[default_payment_method]"
                                      payment-method})))))

;; used for testing
(defn delete-customer-card!
  "Delete a card from a customer"
  [stripe-id source-id]
  (http/delete (str stripe-url "/customers/" stripe-id "/sources/" source-id)
               default-req))

(defn delete-customer!
  "Delete stripe customer entry for user"
  [{:keys [stripe-id user-id]}]
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

(defn list-subscriptions [& {:keys [limit] :or {limit 10}}]
  (stripe-get "/subscriptions" {:limit (str limit)}))

(defn delete-subscription! [subscription-id]
  (stripe-delete (str "/subscriptions/" subscription-id)))

(defn- delete-all-subscriptions! [& {:keys [limit] :or {limit 10}}]
  (if (and (re-matches #"pk_test_.*" (env :stripe-public-key))
           (re-matches #"sk_test_.*" (env :stripe-secret-key)))
    (let [subscriptions (:data (list-subscriptions :limit limit))
          subscription-ids (doall (map #(-> % :id) subscriptions))]
      (doall (map delete-subscription! subscription-ids))
      "done")
    (log/info (str "Error in " (current-function-name) ": " "attempt to run with non-test keys"))))

(defn get-plans
  "Get all site plans"
  []
  (execute-action (plans/get-all-plans)))

(defn get-plan-id [nickname]
  (:id (first (->> (:data (get-plans))
                   (filter #(= nickname (:nickname %)))))))

;; prorating does occur, but only on renewal day (e.g. day payment is due)
;; it is not prorated at the time of upgrade/downgrade
;; https://stripe.com/docs/subscriptions/upgrading-downgrading
(defn create-subscription-user!
  "Create a subscription using the basic plan for user. This subscription is used for all subsequent subscription plans. Return the stripe response."
  [{:keys [user-id stripe-id]}]
  (let [{:keys [plan id error] :as response}
        (stripe-post  "/subscriptions"
                      {"customer" stripe-id
                       "items[0][plan]" (get-plan-id default-plan)
                       "prorate" false
                       "expand[]" "latest_invoice.payment_intent"})]
    (when-not error
      (db-plans/add-user-to-plan! user-id (:id plan) id))
    response))

(defn create-subscription-org!
  "Create a subscription using the basic plan for group-id. This
  subscription is used for all subsequent subscription plans. Return
  the stripe response."
  [group-id stripe-id]
  (let [{:keys [plan id error] :as response}
        (stripe-post "/subscriptions"
                     {"customer" stripe-id
                      "items[0][plan]" (get-plan-id default-plan)
                      "prorate" false
                      "expand[]" "latest_invoice.payment_intent"})]
    (when-not error
      (db-plans/add-group-to-plan! group-id (:id plan) id))
    response))

;; not needed for main application, needed only in tests
(defn ^:unused unsubscribe-customer!
  "Unsubscribe a user. This just removes user from all plans"
  [stripe-id]
  ;; need to remove the user from plan_user table!!!
  (execute-action (subscriptions/unsubscribe-customer
                   (common/customer stripe-id))))
;; https://stripe.com/docs/billing/subscriptions/quantities#setting-quantities

(defn support-project-monthly!
  "User supports a project-id for amount (integer cents). Does not
  handle overhead of increasing / decreasing already supported
  projects"
  [{:keys [user-id stripe-id] :as user} project-id amount]
  (let [{:keys [body status] :as stripe-response}
        (http/post (str stripe-url "/subscriptions")
                   (assoc default-req
                          :form-params
                          {"customer" stripe-id
                           "items[0][plan]" (:id (db-plans/stripe-support-project-plan))
                           "items[0][quantity]" amount
                           "metadata[project-id]" project-id}))]
    (if (= status 200)
      ;; success
      (let [{:keys [id created customer quantity status] :as body-map}
            (json/read-str body :key-fn keyword)]
        (db-plans/upsert-support! {:id id
                                   :project-id project-id
                                   :user-id user-id
                                   :stripe-id customer
                                   :quantity quantity
                                   :status status
                                   :created (some-> created util/to-clj-time)})
        body-map)
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
  (stripe-get (str "/subscriptions/" sub-id)))

(defn get-subscription-item
  "Given a sub-id, get the first subscription item"
  [sub-id]
  (let [subscription (get-subscription sub-id)]
    (when-not (:error subscription)
      (-> subscription :items :data first :id))))

(defn update-subscription-item!
  "Update subscription item with id with optional quantity and optional
  plan (plan-id)"
  [{:keys [id plan quantity] :or {quantity 1}}]
  (http/post (str stripe-url "/subscription_items/" id)
             (assoc default-req :form-params (cond-> {}
                                               quantity (assoc "quantity" quantity)
                                               plan (assoc "plan" plan)))))

(defn cancel-subscription! [sub-id]
  (let [{:keys [project-id user-id]} (db-plans/lookup-support-subscription sub-id)
        {:keys [status body]} (http/delete (str stripe-url "/subscriptions/" sub-id)
                                           default-req)]
    (if (= status 200)
      ;; everything is ok, cancel this subscription
      (let [{:keys [id created customer quantity status]}
            (json/read-str body :key-fn keyword)]
        (db-plans/upsert-support! {:id id
                                   :project-id project-id
                                   :user-id user-id
                                   :stripe-id customer
                                   :quantity quantity
                                   :status status
                                   :created (some-> created util/to-clj-time)}))
      ;; there is an error, report it
      (:error (json/read-str body :key-fn keyword)))))

(defn support-project-once!
  "Make a one-time contribution to a project for amount"
  [{:keys [stripe-id user-id] :as user} project-id amount]
  (try (let [transaction-source (:stripe-charge funds/transaction-source-descriptor)
             {:keys [id created] :as stripe-response}
             (execute-action (merge (charges/create-charge (common/money-quantity amount "usd")
                                                           (common/description "one time")
                                                           (common/customer stripe-id))
                                    {"metadata" {"project-id" project-id}}))]
         (if (and id created)
           ;; charge was a success, insert into the db
           (do (funds/create-project-fund-entry! {:project-id project-id
                                                  :user-id user-id
                                                  :transaction-id id
                                                  :transaction-source transaction-source
                                                  :amount amount
                                                  :created (some-> created util/to-clj-time)})
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
(defn pay-stripe-user! [stripe-account-id amount]
  (http/post (str stripe-url "/transfers")
             (assoc default-req :form-params {"amount" amount
                                              "currency" "usd"
                                              "destination" stripe-account-id})))

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
        balance-transaction (:balance_transaction body)]
    (http/get (str stripe-url "/balance/history/" balance-transaction) default-req)))

(defn get-setup-intent []
  (:body (http/post (str stripe-url "/setup_intents") default-req)))

(defn get-invoice [invoice-id]
  (stripe-get (str "/invoices/" invoice-id)))

(defn get-payment-intent [intent-id]
  (stripe-get (str "/payment_intents/" intent-id)))

;; "sub_FzYzrXeEK3bHOK"

(defn get-subscription-latest-invoice-intent [subscription-id]
  (-> (get-subscription subscription-id)
      :latest_invoice
      (get-invoice)
      :payment_intent
      (get-payment-intent)))

(defn get-invoices [stripe-id]
  (stripe-get "/invoices" {:customer stripe-id}))

(defn get-invoice-ids [stripe-id]
  (map :id (:data (get-invoices stripe-id))))

;; "You can't delete invoices created by subscriptions."
(defn delete-invoice! [invoice-id]
  (stripe-delete (str "/invoices/" invoice-id)))
