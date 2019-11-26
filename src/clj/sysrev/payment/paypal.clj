(ns sysrev.payment.paypal
  (:require [clj-http.client :as client]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [sysrev.config.core :refer [env]]
            [sysrev.project.funds :as funds]
            [sysrev.util :as util]))

;; entrypoint for dashboard:
;; https://developer.paypal.com/developer/accounts/
;; when you create a sandbox account,
;; you will need to login with the sandbox account you created to activate it

;; when the application runs out of money in sandbox mode, clone the account
;; https://developer.paypal.com/developer/accounts/

;; https://developer.paypal.com/developer/applications/
;; note: you WILL have to update paypal-client-id and paypal-secret for the app

;; https://developer.paypal.com/developer/applications
;; REST API apps -> click on <app-name>
;; Client ID
(defn paypal-client-id [] (env :paypal-client-id))
;; Secret (click 'Show')
(defn paypal-secret [] (env :paypal-secret))

(defn paypal-url [] (env :paypal-url))

;; this is for client code
(defn paypal-env [] (condp = (paypal-url)
                      "https://api.sandbox.paypal.com"  "sandbox"
                      "https://api.paypal.com"          "production"))

(defonce current-access-token (atom nil))

(defn get-access-token []
  (-> (client/post (str (paypal-url) "/v1/oauth2/token")
                   {:headers {"Accept" "application/json"
                              "Accept-Language" "en_US"}
                    :basic-auth [(paypal-client-id) (paypal-secret)]
                    :form-params {"grant_type" "client_credentials"}
                    :as :json
                    :throw-exceptions false})
      :body))

(defmacro paypal-oauth-request
  [body]
  `(do (when (empty? (:access_token @current-access-token))
         (reset! current-access-token (get-access-token)))
       (let [response# ~body]
         (cond (and (= "invalid_token"
                       (get-in response# [:body :error]))
                    ;; note: this error_description could possibly change
                    (= "Access Token not found in cache"
                       (get-in response# [:body :error_description])))
               (do (reset! current-access-token (get-access-token))
                   ~body)
               :else response#))))

(defn default-headers
  [access-token]
  {"Authorization" (str "Bearer " access-token)})

;; https://developer.paypal.com/docs/api/payments.payouts-batch/v1/#payouts_create_request
(defn payout-item
  "a payout-item for use in amount"
  [{:keys [user-id email]} amount]
  {:recipient_type "EMAIL"
   :amount {:value (str (util/round 2 (/ amount 100)))
            :currency "USD"}
   :receiver email
   :sender_item_id  user-id})

;; in body {:batch_header {:payout_batch_id}}
(defn send-payout!
  "Send a payout to a user"
  [user amount]
  (client/post (str (paypal-url) "/v1/payments/payouts")
               {:content-type :json
                :headers (default-headers (:access_token @current-access-token))
                :form-params {:sender_batch_header
                              {:sender_batch_id (str (gensym 1))
                               :email_subject "You've been paid by InSilica"
                               :email_message "Thank you for your work on sysrev.com, we've sent your payment!"}
                              :items [(payout-item user amount)]}
                :throw-exceptions false
                :as :json
                :coerce :always}))

(defn date->paypal-start-date
  [date]
  (->> date (f/parse (f/formatters :date)) .toString))

(defn date->paypal-end-date
  [date]
  (-> (f/parse (f/formatter :date) date)
      (t/plus (t/hours 23))
      (t/plus (t/minutes 59))
      (t/plus (t/seconds 59))
      .toString))

(defn paypal-date->unix-epoch
  [paypal-date]
  (-> (f/parse (f/formatter :date-time-no-ms) paypal-date)
      c/to-epoch))

;; this won't show direct deposits
(defn get-transactions
  "Get the transactions for the account from start-date to end-date in the format of YYYY-MM-dd"
  [& {:keys [start-date end-date]
      :or {start-date "2018-01-01" end-date "2018-05-11"}}]
  (client/get (str (paypal-url) "/v1/reporting/transactions")
              {:content-type :json
               :headers (default-headers (:access_token @current-access-token))
               :query-params {"start_date" (date->paypal-start-date start-date)
                              "end_date" (date->paypal-end-date end-date)}
               :throw-exceptions false
               :as :json
               :coerce :always}))

(defn get-transactions-max
  []
  (let [today (f/unparse (f/formatter :year-month-day) (t/now))
        thirty-one-days-ago (-> (t/now) (t/minus (t/days 31))
                                (->> (f/unparse (f/formatter :year-month-day))))]
    (get-transactions :start-date thirty-one-days-ago :end-date today)))


;; a payment-response from get-payment
;; (def payment-response (-> (get-payment "PAY-7EP79122MX509154MLPZOESQ") (paypal-oauth-request)))

;; https://developer.paypal.com/docs/api/payments/v1/#definition-payment
;; (-> (get-in payment-response [:body]))
;; the payment object contains info about the state of the payment (see definition-payment for more info)
;; (-> (get-in payment-response [:body :state]))

;; https://developer.paypal.com/docs/api/payments/v1/#definition-sale
;; (-> (get-in payment-response [:body :transactions]) first :related_resources first) ; this is the sale object
;; possible states: completed, partially_refunded, pending, refunded, denied.
;; the sale object contains the state related to the payment (see definition-sale for more info)
;; (-> (get-in payment-response [:body :transactions]) first :related_resources first :sale :state)

;; https://developer.paypal.com/docs/api/payments/v1/#payment_get
(defn get-payment
  [payment-id]
  (client/get (str (paypal-url) "/v1/payments/payment/" payment-id)
              {:content-type :json
               :headers (default-headers (:access_token @current-access-token))
               :as :json
               :coerce :always}))

(defn get-order
  [order-id]
  (client/get (str (paypal-url) "/v2/checkout/orders/" order-id)
              {:content-type :json
               :headers (default-headers (:access_token @current-access-token))
               :throw-exceptions false
               :as :json
               :coerce :always}))

(defn process-order
  [order-resp]
  (let [purchase-unit (-> order-resp :body :purchase_units first)
        capture (-> purchase-unit :payments :captures first)
        amount (get-in capture [:amount :value])
        status (:status capture)
        create-time (:create_time capture)]
    {:amount (-> amount read-string (* 100) (Math/round))
     :status status
     :created (util/to-clj-time (paypal-date->unix-epoch create-time))}))

(defn check-transaction!
  "Given a payment-id, check to see what the current status of the sale
  is. Update its status if it has changed, if it has changed to
  complete, insert it into the project-fund"
  [payment-id]
  (let [{:keys [status project-id user-id amount transaction-id
                transaction-source]} (funds/get-pending-payment payment-id)
        payment-response (paypal-oauth-request (get-payment payment-id))
        sale-state (-> (get-in payment-response [:body :transactions])
                       first :related_resources first :sale :state)]
    (cond (= sale-state "completed") ; payment completed
          (do (funds/update-project-fund-pending-entry!
               {:transaction-id payment-id :status sale-state})
              (funds/create-project-fund-entry!
               {:project-id project-id :user-id user-id :amount amount
                :transaction-id transaction-id :transaction-source transaction-source}))
          (not= sale-state status) ; status has changed, update it
          (funds/update-project-fund-pending-entry!
           {:transaction-id transaction-id :status sale-state}))))
