(ns sysrev.paypal
  (:require [clj-http.client :as client]
            [environ.core :refer [env]]
            [sysrev.util :as util]))

(def paypal-client-id (or (System/getProperty "PAYPAL_CLIENT_ID")
                          (env :paypal-client-id)))
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

(defn default-headers
  [access-token]
  {"Authorization" (str "Bearer " access-token)})

;; https://developer.paypal.com/docs/api/payments.payouts-batch/v1/#payouts_create_request
(defn create-payout-item
  "Receiver is an email address, amount is integer amount of cents, user-id is the sysrev user id"
  [receiver amount user-id]
  {:recipient_type "EMAIL"
   :amount {:value  (util/round 2 (/ amount 100))
            :currency "USD"}
   :receiver receiver
   :sender_item_id  user-id})

(defn send-payout
  "Send a payout to a user"
  [receiver amount user-id]
  (-> (client/post (str paypal-url "/v1/payments/payouts")
                   {:content-type :json
                    :headers (default-headers @current-access-token)
                    :form-params {:sender_batch_header
                                  {:sender_batch_id (str (gensym 1))
                                   :email_subject "You've been paid by InSilica"
                                   :email_message "Thank you for your work on SysRev.com, we've sent your payment!"}
                                  :items [(create-payout-item receiver amount user-id)]}
                    :throw-exceptions false
                    :debug true
                    :debug-body true})))
