(ns sysrev.test.e2e.plans-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [etaoin.keys :as keys]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.payment.stripe :as stripe]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.user.interface :as user]))

(defn send-keypresses! [driver keys]
  (->> (reduce (comp #(ea/add-pause % 20) ea/add-key-press)
               (ea/add-pause (ea/make-key-input) 20)
               keys)
       (ea/perform-actions driver)))

(defn tab! [driver]
  (ea/perform-actions driver (-> (ea/make-key-input) (ea/add-key-press keys/tab))))

;; The Stripe iframes are inconsistent, so this is a more reliable way
;; to fill out the form.

(defn fill-out-stripe-form! [driver card-num exp-date cvc]
  (doto driver
    tab!
    (send-keypresses! card-num)
    tab!
    (send-keypresses! exp-date)
    tab!
    (send-keypresses! cvc)))

(defn create-customer! [user]
  (user/create-user-stripe-customer! user)
  (let [{:keys [stripe-id] :as user} (user/user-by-email (:email user))]
    (stripe/create-subscription-user! user)
    (ea/wait-predicate #(stripe/get-customer stripe-id))))

(defn wait-for-stripe-form! [driver]
  (ea/wait-predicate #(< 2 (count (ea/query-all driver {:tag :iframe}))))
  ;; Wait due to flakieness
  (ea/wait driver 3))

(deftest ^:optional test-plans-page
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user (test/create-test-user system)]
      (create-customer! user)
      (account/log-in test-resources user)
      (e/go test-resources "/user/plans")
      (doto driver
        wait-for-stripe-form!
        (et/is-click-visible {:fn/text "Pay Monthly"})
        (fill-out-stripe-form! "4242424242424242" "0130" "123")
        (et/is-click-visible {:css ".button.use-card"})
        (et/is-click-visible {:css ".button.upgrade-plan"})
        (et/is-wait-visible [:user_billing {:fn/has-text "Team Pro"}])))))

(deftest ^:optional test-plans-page-validation
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user (test/create-test-user system)]
      (create-customer! user)
      (account/log-in test-resources user)
      (e/go test-resources "/user/plans")
      (doto driver
        wait-for-stripe-form!
        (et/is-click-visible {:css ".button.use-card"})
        (et/is-wait-visible {:fn/text "Your card number is incomplete."})
        (et/is-wait-visible {:fn/text "Your card's expiration date is incomplete."})
        (et/is-wait-visible {:fn/text "Your card's security code is incomplete."})))))

(deftest ^:optional test-card-declined
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user (test/create-test-user system)]
      (create-customer! user)
      (account/log-in test-resources user)
      (e/go test-resources "/user/plans")
      (doto driver
        wait-for-stripe-form!
        (et/is-click-visible {:fn/text "Pay Monthly"})
        (fill-out-stripe-form! "4000000000000002" "0130" "123")
        (et/is-click-visible {:css ".button.use-card"})
        (et/is-wait-visible {:fn/has-text "Your card was declined"})))))

(deftest ^:optional test-processing-error
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user (test/create-test-user system)]
      (create-customer! user)
      (account/log-in test-resources user)
      (e/go test-resources "/user/plans")
      (doto driver
        wait-for-stripe-form!
        (et/is-click-visible {:fn/text "Pay Monthly"})
        (fill-out-stripe-form! "4000000000000119" "0130" "123")
        (et/is-click-visible {:css ".button.use-card"})
        (et/is-wait-visible {:fn/has-text "An error occurred while processing your card"})))))
