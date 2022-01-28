(ns sysrev.payment.stripe-test
  (:require
   [clojure.test :refer :all]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.payment.plans :as plans]
   [sysrev.payment.stripe :as stripe]
   [sysrev.shared.plans-info :as plans-info]
   [sysrev.test.core :as test]
   [sysrev.user.interface :as user]))

(defn customer-plan [customer]
  (some-> customer :subscriptions :data first :items :data first :plan))

(deftest ^:integration ^:stripe test-register-and-check-basic-plan
  (test/with-test-system [system {}]
    (let [{:keys [email user-id] :as user} (test/create-test-user system)
          _ (user/create-user-stripe-customer! user)
          {:keys [stripe-id] :as user} (user/user-by-email email)]
      (stripe/create-subscription-user! user)
      (et/is-wait-pred #(stripe/get-customer stripe-id)
                       {}
                       "Stripe customer exists")
      (let [customer (stripe/get-customer stripe-id)]
        (is (= email (:email customer)))
        (is (= plans-info/default-plan (:nickname (customer-plan customer)))
            "Stripe thinks the user is subscribed to Basic")
        (is (= plans-info/default-plan (:nickname (plans/user-current-plan user-id)))
            "We think the user is subscribed to Basic")))))
