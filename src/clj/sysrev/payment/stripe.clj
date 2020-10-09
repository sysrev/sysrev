(ns sysrev.payment.stripe
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [clj-http.client :as http]
            [sysrev.config :refer [env]]
            [sysrev.payment.plans :as db-plans]
            [sysrev.project.funds :as funds]
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

(defn stripe-get [uri & [query-params]]
  (:body (http/get (str stripe-url uri)
                   (cond-> default-req
                     query-params
                     (assoc :query-params (walk/stringify-keys query-params))))))

(defn stripe-post [uri & [form-params]]
  (:body (http/post (str stripe-url uri)
                    (assoc default-req
                           :form-params
                           (walk/stringify-keys form-params)))))

(defn stripe-delete [uri]
  (:body (http/delete (str stripe-url uri) default-req)))

(defn create-customer!
  "Create a stripe customer"
  [& {:keys [email description]}]
  (stripe-post "/customers"
               (cond-> {}
                 email (assoc :email email)
                 description (assoc :description description))))

(defn get-customer
  "Get customer associated with stripe customer id"
  [stripe-id]
  (when-not (nil? stripe-id)
    (stripe-get (str "/customers/" stripe-id))))

(defn get-payment-method
  "Get the payment-method by its id "
  [id]
  (stripe-get (str "/payment_methods/" id)))

(defn get-customer-invoice-default-payment-method
  [stripe-id]
  (when-let [id (get-in (get-customer stripe-id)
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

(defn delete-customer!
  "Delete stripe customer entry for user"
  [{:keys [stripe-id user-id]}]
  (if stripe-id
    (stripe-delete (str "/customers/" stripe-id))
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
(defn delete-all-customers! []
  (assert (and (re-matches #"pk_test_.*" (env :stripe-public-key))
               (re-matches #"sk_test_.*" (env :stripe-secret-key)))
          (str "Error in" (current-function-name) "- attempt to run with non-test keys"))
  ;; note that even the limit-count is hard coded here
  ;; you may have to run this function more than once
  ;; if you have created a lot of stray stripe customers
  (let [customers (-> (stripe-get "/customers"
                                  {:limit 100})
                      :data)]
    (mapv #(delete-customer! {:stripe-id (:id %)
                              :user-id "delete-all-customers! override"})
          customers)))

(defn ^:unused list-subscriptions [& {:keys [limit] :or {limit 10}}]
  (stripe-get "/subscriptions" {:limit (str limit)}))

(defn delete-subscription! [subscription-id]
  (stripe-delete (str "/subscriptions/" subscription-id)))

(defn delete-all-subscriptions! [& {:keys [limit] :or {limit 10}}]
  (if (and (re-matches #"pk_test_.*" (env :stripe-public-key))
           (re-matches #"sk_test_.*" (env :stripe-secret-key)))
    (let [subscriptions (:data (list-subscriptions :limit limit))
          subscription-ids (doall (map #(-> % :id) subscriptions))]
      (doall (map delete-subscription! subscription-ids))
      "done")
    (log/infof "Error in %s: attempt to run with non-test keys"
               (current-function-name))))

(defn get-plans
  "Get all site plans"
  []
  (stripe-get "/plans"))

(defn get-plan-id [nickname]
  (:id (first (->> (:data (get-plans))
                   (filter #(= nickname (:nickname %)))))))

;; prorating does occur, but only on renewal day (e.g. day payment is due)
;; it is not prorated at the time of upgrade/downgrade
;; https://stripe.com/docs/subscriptions/upgrading-downgrading
(defn create-subscription-user!
  "Create a subscription using the basic plan for user. This
  subscription is used for all subsequent subscription plans. Return
  the stripe response."
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

;; https://stripe.com/docs/billing/subscriptions/quantities#setting-quantities

(defn ^:unused support-project-monthly!
  "User supports a project-id for amount (integer cents). Does not
  handle overhead of increasing / decreasing already supported
  projects"
  [{:keys [user-id stripe-id] :as _user} project-id amount]
  (let [{:keys [body status]}
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
  [{:keys [id plan-id quantity] :or {quantity 1}}]
  (stripe-post (str "/subscription_items/" id)
               (cond-> {}
                 quantity (assoc "quantity" quantity)
                 plan-id (assoc "plan" plan-id))))

(defn ^:unused cancel-subscription! [sub-id]
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

(defn ^:unused support-project-once!
  "Make a one-time contribution to a project for amount"
  [{:keys [user-id stripe-id] :as _user} project-id amount]
  (try (let [transaction-source (:stripe-charge funds/transaction-source-descriptor)
             {:keys [id created] :as stripe-response}

             ;; this will need to be upgraded to use PaymentIntents
             #_(execute-action (merge (charges/create-charge (common/money-quantity amount "usd")
                                                             (common/description "one time")
                                                             (common/customer stripe-id))
                                      {"metadata" {"project-id" project-id}}))
             "foo"]
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
(defn ^:unused finalize-stripe-user!
  "finalize the stripe user on stripe, return the stripe customer-id"
  [stripe-code]
  (http/post "https://connect.stripe.com/oauth/token"
             (assoc default-req
                    :form-params
                    {"client_secret" stripe-secret-key
                     "code" stripe-code
                     "grant_type" "authorization_code"})))

;; https://stripe.com/docs/api/transfers/create
(defn ^:unused pay-stripe-user! [stripe-account-id amount]
  (stripe-post "/transfers"
             {:amount amount
              :currency "usd"
              :destination stripe-account-id}))

;;https://stripe.com/docs/api/balance/balance_retrieve
(defn ^:unused current-balance []
  (stripe-get "/balance"))

;;; charge for payments: (- 1000 (* (/ 2.9 100) 1000) 30)

;; https://stripe.com/docs/api/transfers/retrieve
(defn ^:unused retrieve-transfer [transfer-id]
  (stripe-get (str "/transfers/" transfer-id)))

(defn ^:unused retrieve-charge [charge-id]
  (stripe-get (str "/charges/" charge-id)))

(defn ^:unused transaction-history
  "Given a charge-id (ch_*), return the balance history"
  [charge-id]
  (let [{:keys [body]} (retrieve-charge charge-id)
        balance-transaction (:balance_transaction body)]
    (http/get (str stripe-url "/balance/history/" balance-transaction) default-req)))

(defn get-setup-intent []
  (stripe-post "/setup_intents"))

(defn get-invoice [invoice-id]
  (stripe-get (str "/invoices/" invoice-id)))

(defn get-payment-intent [intent-id]
  (stripe-get (str "/payment_intents/" intent-id)))

(defn ^:unused get-subscription-latest-invoice-intent [subscription-id]
  (-> (get-subscription subscription-id)
      :latest_invoice
      (get-invoice)
      :payment_intent
      (get-payment-intent)))

(defn ^:unused get-invoices [stripe-id]
  (stripe-get "/invoices" {:customer stripe-id}))

(defn ^:unused get-invoice-ids [stripe-id]
  (map :id (:data (get-invoices stripe-id))))

;; "You can't delete invoices created by subscriptions."
(defn ^:unused delete-invoice! [invoice-id]
  (stripe-delete (str "/invoices/" invoice-id)))
