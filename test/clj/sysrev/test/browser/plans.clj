(ns sysrev.test.browser.plans
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [sysrev.api :as api]
            [sysrev.config.core :refer [env]]
            [sysrev.payment.plans :as plans]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.payment.stripe :as stripe]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;; for manual testing purposes, this is handy:
;;
#_(let [email "james@insilica.co"]
  (b/cleanup-test-user! :email email :groups true))

;; recreate
#_(let [email "james@insilica.co" password "testing"]
    (b/create-test-user :email email :password password)
    (users/create-user-stripe-customer! (user-by-email email))
    (stripe/create-subscription-user! (user-by-email email)))

(def use-card "form.StripeForm button.ui.button.use-card")

(def upgrade-link (xpath "//a[text()='Upgrade']"))

(defn click-use-card [& {:keys [wait delay error?]
                         :or {wait true delay 50 error? false}}]
  (b/wait-until-loading-completes :timeout 10000 :interval 100)
  (b/click use-card :displayed? true :timeout 15000)
  (log/info "clicked \"Use Card\"")
  (b/wait-until-loading-completes :pre-wait delay))

(defn click-upgrade-plan [& {:keys [delay] :or {delay 50}}]
  (b/wait-until-loading-completes)
  (b/wait-until-displayed ".ui.button.upgrade-plan" 30000 50)
  (b/click ".ui.button.upgrade-plan" :displayed? true :timeout 15000)
  (b/is-soon (not (taxi/exists? (b/not-disabled ".ui.button.upgrade-plan"))))
  (log/info "clicked \"Upgrade Plan\"")
  (b/wait-until-loading-completes :pre-wait delay))

(defn get-user-customer [email]
  (some-> email (user-by-email) :stripe-id
          (stripe/get-customer)))

(defn customer-plan [customer]
  (some-> customer :subscriptions :data first :items :data first :plan))

(defn user-stripe-plan [email]
  (some-> (get-user-customer email) (customer-plan) :nickname))

(defn user-db-plan [email]
  (some-> email (user-by-email) :user-id (plans/user-current-plan) :name))

(defn wait-until-stripe-id
  "Wait until stripe has customer entry for email."
  [email]
  (test/wait-until #(:email (get-user-customer email)) 5000 100))

(defn wait-until-plan
  "Wait until stripe customer entry matches plan value."
  [email plan]
  (test/wait-until #(= plan (user-stripe-plan email)) 5000 100))

(defn label-input
  "Given a label, return an xpath for its input"
  [label]
  (xpath "//label[contains(text(),'" label "')]/descendant::input"))

(defn error-msg-xpath
  "return an xpath for a error message div with error-msg"
  [error-msg]
  (xpath "//div[contains(@class,'red') and contains(text(),\"" error-msg "\")]"))

(defn user-subscribe-to-unlimited
  [email password]
  (when-not (get-user-customer email)
    (log/info (str "Stripe Customer created for " email))
    (user/create-user-stripe-customer! (user-by-email email)))
  (wait-until-stripe-id email)
  (stripe/create-subscription-user! (user-by-email email))
  (Thread/sleep 100)
  (nav/log-in email password)
  ;; go to plans
  (b/click "#user-name-link" :delay 50)
  (b/click "#user-billing" :delay 50)
  (b/click ".button.nav-plans.subscribe" :delay 50 :displayed? true)
  (b/click "a.payment-method.add-method" :delay 50)
  ;; enter payment information
  (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
  (click-use-card :delay 50)
  ;; upgrade to unlimited
  (click-upgrade-plan :delay 50)
  ;; this time is goes through, confirm we are subscribed to the
  ;; Unlimited plan now
  (b/wait-until-displayed ".button.nav-plans.unsubscribe")
  (log/info "found \"Unsubscribe\" button"))

;; need to disable sending emails in this test (for register user via web)
(deftest-browser register-and-check-basic-plan-subscription
  (and (test/db-connected?) (not (test/remote-test?)))
  [{:keys [email password]} b/test-login
   get-test-user #(user-by-email email)
   get-customer #(get-user-customer email)]
  (do (user/create-user-stripe-customer! (get-test-user))
      (stripe/create-subscription-user! (get-test-user))
      ;; after registering, does the stripe customer exist?
      (wait-until-stripe-id email)
      (is (= email (:email (get-customer))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email stripe/default-plan)
      (is (= stripe/default-plan (user-stripe-plan email)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= stripe/default-plan (user-db-plan email))))
  :cleanup (b/cleanup-test-user! :email email))

;; need to disable sending emails in this test
(deftest-browser register-and-subscribe-to-paid-plans
  (and (test/db-connected?) (not (test/remote-test?)))
  [{:keys [email password]} b/test-login
   get-test-user #(user-by-email email)
   get-customer #(get-user-customer email)
   get-stripe-plan #(user-stripe-plan email)
   get-db-plan #(user-db-plan email)]
  (do (assert stripe/stripe-secret-key)
      (assert stripe/stripe-public-key)
      (user/create-user-stripe-customer! (get-test-user))
      (stripe/create-subscription-user! (get-test-user))
      (wait-until-stripe-id email)
      ;; after registering, does the stripe customer exist?
      (is (= email (:email (get-customer))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email stripe/default-plan)
      (is (= stripe/default-plan (get-stripe-plan)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= stripe/default-plan (get-db-plan)))
      (nav/log-in)
;;; upgrade plan
      (b/click "#user-name-link")
      (b/click "#user-billing")
      (b/click ".button.nav-plans.subscribe" :delay 50 :displayed? true)
      (b/click "a.payment-method.add-method" :delay 50)
;;; payment method
      ;; wait until a card number is available for input
      (b/wait-until-exists (label-input "Card Number"))
      ;; just try to 'Use Card', do we have all the error messages we would expect?
      (click-use-card)
      ;; incomplete fields are shown
      (b/is-soon (every? #(taxi/exists? (error-msg-xpath %))
                         [bstripe/incomplete-card-number-error
                          bstripe/incomplete-expiration-date-error
                          bstripe/incomplete-security-code-error])
                 10000 100)
      (if (test/full-tests?)
        (log/info "running full stripe tests")
        (log/info "skipping full stripe tests"))
      (when (test/full-tests?)
        (doseq [{:keys [name error number cc-fields declined]}
                [{:name "fail-lunh-check-cc"
                  :error bstripe/invalid-card-number-error
                  :number bstripe/fail-luhn-check-cc
                  :cc-fields {}}
                 {:name "cvc-check-fail"
                  :error bstripe/invalid-security-code-error
                  :number bstripe/cvc-check-fail-cc}
                 {:name "card-declined-cc"
                  :error bstripe/card-declined-error
                  :number bstripe/card-declined-cc}
                 {:name "incorrect-cvc-cc"
                  :error bstripe/invalid-security-code-error
                  :number bstripe/incorrect-cvc-cc}
                 {:name "expired-card-cc"
                  :error bstripe/card-expired-error
                  :number bstripe/expired-card-cc}
                 {:name "processing-error-cc"
                  :error bstripe/card-processing-error
                  :number bstripe/processing-error-cc}
                 ;; attach-success-charge-fail-cc: in this case, the
                 ;; card is attached to the customer but they won't be
                 ;; able to subscribe because the card doesn't go
                 ;; through
                 ;;
                 ;; FIX: the decline part of this isn't working
                 #_ {:name "attach-success-charge-fail-cc"
                     :number bstripe/attach-success-charge-fail-cc
                     :cc-fields {}
                     :declined true}
                 ;; FIX: the decline part of this also isn't working
                 #_ {:name "highest-risk-fraudulent-cc"
                     :number bstripe/highest-risk-fraudulent-cc
                     :cc-fields {}
                     :declined true}]]
          (log/info "testing" name)
          (if cc-fields
            (bstripe/enter-cc-information (merge {:cardnumber number} cc-fields))
            (bstripe/enter-cc-number number))
          (when (b/try-wait b/wait-until #(taxi/exists? (b/not-disabled use-card)) 250 20)
            (click-use-card :error? (boolean error)))
          (when error
            (when (string? error)
              (b/is-soon (taxi/exists? (error-msg-xpath error))))
            #_ (b/is-soon (taxi/exists? (str use-card ".disabled"))))
          (when declined
            (b/wait-until-loading-completes :pre-wait 100)
            (click-upgrade-plan :delay 50)
            (b/is-soon
             (taxi/exists?
              {:xpath "//p[contains(text(),'Your card was declined.')]"}))
            (b/click "a.payment-method.change-method" :delay 50)
            (b/wait-until-displayed (b/not-disabled use-card)))))
;;; finally, update with a valid cc number and see if we can subscribe to plans
      (log/info "testing valid card info")
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
      (click-use-card :delay 50)
      ;; try to subscribe again
      (click-upgrade-plan)
      ;; this time is goes through, confirm we are subscribed to the
      ;; Unlimited plan now
      (b/wait-until-displayed ".button.nav-plans.unsubscribe")
      (log/info "found \"Unsubscribe\" button")
      ;; Let's check to see if our db thinks the customer is subscribed to the Unlimited
      (is (= "Unlimited_User" (get-db-plan)))
      ;; Let's check that stripe.com thinks the customer is subscribed to the Unlimited plan
      (is (= "Unlimited_User" (get-stripe-plan)))
;;; Subscribe back down to the Basic Plan
      (b/click ".button.nav-plans.unsubscribe")
      (b/click ".button.unsubscribe-plan")
      (b/click ".button.nav-plans.subscribe" :displayed? true)
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email stripe/default-plan)
      (is (= stripe/default-plan (get-stripe-plan)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= stripe/default-plan (get-db-plan))))
  :cleanup (b/cleanup-test-user! :email email))

