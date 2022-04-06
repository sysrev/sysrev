(ns sysrev.payment.stripe-test
  (:require [clojure.test :refer :all]
            [sysrev.payment.plans :as db-plans]
            [sysrev.payment.stripe :as stripe]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.test.core :as test]
            [sysrev.user.interface :as user]))

(defn create-subscription! [user-id stripe-id plan-id]
  (let [{:keys [id error]}
        (stripe/stripe-post  "/subscriptions"
                             {"customer" stripe-id
                              "items[0][plan]" plan-id
                              "prorate" false
                              "expand[]" "latest_invoice.payment_intent"})]
    (when error
      (throw (ex-info "Stripe API error" {:error error})))
    (db-plans/add-user-to-plan! user-id plan-id id)
    id))

(defn create-test-payment-method! []
  (stripe/stripe-post
   "/payment_methods"
   {"card[cvc]" "314"
    "card[exp_month]" "4"
    "card[exp_year]" "2028"
    "card[number]" "4242424242424242"
    "type" "card"}))

(defn customer-plan [customer]
  (some-> customer :subscriptions :data first :items :data first :plan))

(deftest ^:integration ^:stripe test-register-and-check-plan
  (test/with-test-system [system {}]
    (let [{:keys [email user-id] :as user} (test/create-test-user system)
          _ (user/create-user-stripe-customer! user)
          {:keys [stripe-id] :as user} (user/user-by-email email)]
      (stripe/create-subscription-user! user)
      (let [customer (stripe/get-customer stripe-id)]
        (is (= email (:email customer)))
        (is (= plans-info/default-plan (:nickname (customer-plan customer)))
            "Stripe thinks the user is subscribed to Basic")
        (is (= plans-info/default-plan (:nickname (db-plans/user-current-plan user-id)))
            "We think the user is subscribed to Basic"))
      (testing "When a user has both basic and pro subscriptions, the pro sub is preferred"
        (let [pm-id (:id (create-test-payment-method!))
              pro-id (stripe/get-plan-id plans-info/unlimited-user)]
          (is (string? pm-id))
          (is (not (:error (stripe/update-customer-payment-method! stripe-id pm-id))))
          (create-subscription! user-id stripe-id pro-id)
          (is (plans-info/pro? (:nickname (db-plans/user-current-plan user-id)))))))))
