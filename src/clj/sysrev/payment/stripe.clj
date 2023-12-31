(ns sysrev.payment.stripe
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [sysrev.config :refer [env]]
            [sysrev.payment.plans :as db-plans]
            [honeysql.helpers :as sqlh :refer [select from insert-into values join where]]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.funds :as funds]
            [sysrev.shared.plans-info :as plans-info :refer [default-plan]]
            [sysrev.util :as util :refer [index-by]]))

(defn stripe-public-key []
  (env :stripe-public-key))

;; https://dashboard.stripe.com/account/applications/settings
(defn stripe-client-id []
  (env :stripe-client-id))

(def stripe-url "https://api.stripe.com/v1")

(defn default-req []
  {:basic-auth (env :stripe-secret-key)
   :coerce :always
   :throw-exceptions false
   :as :json})

(defn stripe-get [uri & [query-params]]
  (:body (http/get (str stripe-url uri)
                   (cond-> (default-req)
                     query-params
                     (assoc :query-params (walk/stringify-keys query-params))))))

(defn stripe-post [uri & [form-params]]
  (:body (http/post (str stripe-url uri)
                    (assoc (default-req)
                           :form-params
                           (walk/stringify-keys form-params)))))

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
                            (assoc (default-req)
                                   :form-params {"customer"
                                                 stripe-id}))]
    (if (:error response)
      {:error (:error response)}
      (http/post (str stripe-url "/customers/" stripe-id)
                 (assoc (default-req)
                        :form-params {"invoice_settings[default_payment_method]"
                                      payment-method})))))

(defn get-plans
  "Get all site plans"
  []
  (stripe-get "/plans" {:limit 100}))

(defn get-products
  "Get all site products"
  []
  (stripe-get "/products" {:limit 100}))

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

(defn support-project-monthly!
  "User supports a project-id for amount (integer cents). Does not
  handle overhead of increasing / decreasing already supported
  projects"
  [{:keys [user-id stripe-id] :as _user} project-id amount]
  (let [{:keys [body status]}
        (http/post (str stripe-url "/subscriptions")
                   (assoc (default-req)
                          :form-params
                          {"customer" stripe-id
                           "items[0][plan]" (:id (db-plans/stripe-support-project-plan))
                           "items[0][quantity]" amount
                           "metadata[project-id]" project-id}))]
    (if (= status 200)
      ;; success
      (let [{:keys [id created customer quantity status] :as body-map}
            (util/read-json body)]
        (db-plans/upsert-support! {:id id
                                   :project-id project-id
                                   :user-id user-id
                                   :stripe-id customer
                                   :quantity quantity
                                   :status status
                                   :created (some-> created util/to-clj-time)})
        body-map)
      ;; unknown error
      {:status status :body (util/read-json body)})))

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

(defn cancel-subscription! [sub-id]
  (let [{:keys [project-id user-id]} (db-plans/lookup-support-subscription sub-id)
        {:keys [status body]} (http/delete (str stripe-url "/subscriptions/" sub-id)
                                           (default-req))]
    (if (= status 200)
      ;; everything is ok, cancel this subscription
      (let [{:keys [id created customer quantity status]}
            (util/read-json body)]
        (db-plans/upsert-support! {:id id
                                   :project-id project-id
                                   :user-id user-id
                                   :stripe-id customer
                                   :quantity quantity
                                   :status status
                                   :created (some-> created util/to-clj-time)}))
      ;; there is an error, report it
      (:error (util/read-json body)))))

(defn support-project-once!
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

(defn get-setup-intent []
  (stripe-post "/setup_intents"))

(defn update-subscription [subscription-id]
  (let [subscription (get-subscription subscription-id)]
    (q/modify :plan-user
              {:sub-id (:id subscription)}
              {:status (:status subscription)
               :current-period-start (some-> (:current_period_start subscription) util/to-clj-time)
               :current-period-end (some-> (:current_period_end subscription) util/to-clj-time)})))

(defn update-subscriptions []
  (let [subscriptions (-> (select :sub-id)
                          (from [:plan-user :pu])
                          (join [:stripe-plan :sp] [:= :pu.plan :sp.id])
                          (where [:= :sp.product-name plans-info/premium-product])
                          db/do-query)]
    (doseq [subscription-id subscriptions]
      (update-subscription subscription-id))))

(defn update-stripe-plans-table
  "Update the stripe_plans table based upon what is stored on stripe. We
  never delete plans, even though they may no longer exist on stripe
  so that there is a record of their existence. If a plan is changed
  on the stripe, it is updated here."
  []
  (db/with-transaction
    (let [products (->> (if (#{:dev :test} (:profile env))
                          (try
                            (get-products)
                            (catch java.io.IOException e
                              (log/error "Couldn't get products" (class e))))
                          (get-products))
                        :data
                        (index-by :id))
          plans (->> (if (#{:dev :test} (:profile env))
                       (try
                         (get-plans)
                         (catch java.io.IOException e
                           (log/error "Couldn't get plans in update-stripe-plans-table:" (class e))))
                       (get-plans))
                     :data
                     (mapv #(-> (select-keys % [:nickname :created :id :product :interval :amount :tiers])
                                (assoc :product-name (get-in products [(:product %) :name]))))
                     (mapv #(update % :created util/to-clj-time))
                     (mapv #(update % :tiers db/to-jsonb)))]
      (when-let [valid-plans (->> plans (remove #(nil? (:nickname %))) seq)]
        (-> (insert-into :stripe-plan)
            (values valid-plans)
            (upsert (-> (on-conflict :nickname)
                        (do-update-set :id :created :interval :amount :tiers :product :product-name)))
            db/do-execute)))))

(defn handle-webhook [body]

  (when (= (:type body) "customer.subscription.updated")
    (update-subscription (get-in body [:data :object :id])))

  (when (= (:type body) "customer.subscription.created")
    (let [user-id (q/find-one :web-user {:stripe-id (get-in body [:data :object :customer])} :user-id)
          subscription-items (filter #(= (:object %) "subscription_item") (get-in body [:data :object :items]))]
      (doseq [item subscription-items]
        (db-plans/add-user-to-plan! user-id (get-in item [:price :id]) (get-in body [:data :object :id])))))

  (when (contains? #{"price.created" "price.deleted" "price.updated" "product.created" "product.deleted" "product.updated"} (:type body))
    (update-stripe-plans-table))

  {:success true :handled true})

