(ns sysrev.paypal
  (:require [clj-http.client :as client]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [environ.core :refer [env]]
            [sysrev.util :as util]))

;; entrypoint for dashboard:
;; https://developer.paypal.com/developer/accounts/
;; when you create a sandbox account,
;; you will need to login with the sandbox account you created to activate it

;; when the application runs out of money in sandbox mode, clone the account
;; https://developer.paypal.com/developer/accounts/

;; https://developer.paypal.com/developer/applications/
;; note: you WILL have to udpate paypal-client-id and paypal-secret for the app

;; https://developer.paypal.com/developer/applications
;; REST API apps -> click on <app-name>
;; Client ID
(def paypal-client-id (or (System/getProperty "PAYPAL_CLIENT_ID")
                          (env :paypal-client-id)))
;; Secret (click 'Show')
(def paypal-secret (or (System/getProperty "PAYPAL_SECRET")
                       (env :paypal-secret)))

(def paypal-url (or (System/getProperty "PAYPAL_URL")
                    (env :paypal-url)))

(defonce current-access-token (atom nil))

(defn get-access-token
  []
  (-> (client/post (str paypal-url "/v1/oauth2/token")
                   {:headers {"Accept" "application/json"
                              "Accept-Language" "en_US"}
                    :basic-auth [paypal-client-id paypal-secret]
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
  (-> (client/post (str paypal-url "/v1/payments/payouts")
                   {:content-type :json
                    :headers (default-headers (:access_token @current-access-token))
                    :form-params {:sender_batch_header
                                  {:sender_batch_id (str (gensym 1))
                                   :email_subject "You've been paid by InSilica"
                                   :email_message "Thank you for your work on sysrev.com, we've sent your payment!"}
                                  :items [(payout-item user amount)]}
                    :throw-exceptions false
                    :as :json
                    :coerce :always})))

(defn date->paypal-start-date
  [date]
  (->> date (f/parse (f/formatters :date)) .toString))

(defn date->paypal-end-date
  [date]
  (-> (f/parse (f/formatter :date) date) (t/plus (t/hours 23)) (t/plus (t/minutes 59)) (t/plus (t/seconds 59)) .toString))

;; this won't show direct deposits
(defn get-transactions
  "Get the transactions for the account from start-date to end-date in the format of YYYY-MM-dd"
  [& {:keys [start-date end-date]
      :or {start-date "2018-01-01"
           end-date "2018-05-11"}}]
  (-> (client/get (str paypal-url "/v1/reporting/transactions")
                  {:content-type :json
                   :headers (default-headers (:access_token @current-access-token))
                   :query-params {"start_date" (date->paypal-start-date start-date)
                                  "end_date" (date->paypal-end-date end-date)}
                   :throw-exceptions false
                   :as :json
                   :coerce :always})))

(defn get-transactions-max
  []
  (let [today (f/unparse (f/formatter :year-month-day) (t/now))
        thirty-one-days-ago (-> (t/now) (t/minus (t/days 31))
                                (->> (f/unparse (f/formatter :year-month-day))))]
    (get-transactions :start-date thirty-one-days-ago :end-date today)))
