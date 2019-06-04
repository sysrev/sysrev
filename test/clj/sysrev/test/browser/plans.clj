(ns sysrev.test.browser.plans
  (:require [clj-stripe.customers :as customers]
            [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [sysrev.api :as api]
            [sysrev.config.core :refer [env]]
            [sysrev.db.plans :as plans]
            [sysrev.db.users :as users]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.stripe :as stripe]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def use-card ".button.use-card")
(def upgrade-link (xpath "//a[text()='Upgrade']"))

(defn get-user-customer [email]
  (some-> email (users/get-user-by-email) :stripe-id
          (customers/get-customer)
          (stripe/execute-action)))

(defn customer-plan [customer]
  (some-> customer :subscriptions :data first :items :data first :plan))

(defn user-stripe-plan [email]
  (some-> (get-user-customer email) (customer-plan) :nickname))

(defn user-db-plan [email]
  (some-> email (users/get-user-by-email) :user-id (plans/get-current-plan) :name))

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
  [email]
  (when-not (get-user-customer email)
    (log/info (str "Stripe Customer created for " email))
    (users/create-sysrev-stripe-customer! (users/get-user-by-email email)))
  (wait-until-stripe-id email)
  (stripe/create-subscription-user! (users/get-user-by-email email))
  ;;; go to plans
  (b/click "#user-name-link")
  (b/click "#user-billing")
  (b/click ".button.nav-plans.subscribe" :displayed? true)
  (b/click "a.payment-method.add-method")
  ;; enter payment information
  (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc
                                 :exp-date "0121"
                                 :cvc "123"
                                 :postal "11111"})
  (b/click use-card)
  ;; upgrade to unlimited
  (b/click ".button.upgrade-plan")
  ;; this time is goes through, confirm we are subscribed to the
  ;; Unlimited plan now
  (b/wait-until-displayed ".button.nav-plans.unsubscribe" 10000))

;; need to disable sending emails in this test
(deftest-browser register-and-check-basic-plan-subscription
  (and (test/db-connected?)
       (not= :remote-test (-> env :profile)))
  [{:keys [email password]} b/test-login
   get-user #(users/get-user-by-email email)
   get-customer #(get-user-customer email)]
  (do #_ (b/delete-test-user)
      #_ (nav/register-user)
      (users/create-sysrev-stripe-customer! (get-user))
      (stripe/create-subscription-user! (get-user))
      ;; after registering, does the stripe customer exist?
      (wait-until-stripe-id email)
      (is (= email (:email (get-customer))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email stripe/default-plan)
      (is (= stripe/default-plan (user-stripe-plan email)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= stripe/default-plan (user-db-plan email))))
  :clean-up
  (users/delete-sysrev-stripe-customer! (users/get-user-by-email email)))

;; need to disable sending emails in this test
(deftest-browser register-and-subscribe-to-paid-plans
  (and (test/db-connected?)
       (not= :remote-test (-> env :profile)))
  [{:keys [email password]} b/test-login
   get-user #(users/get-user-by-email email)
   get-customer #(get-user-customer email)
   get-stripe-plan #(user-stripe-plan email)
   get-db-plan #(user-db-plan email)]
  (do (assert stripe/stripe-secret-key)
      (assert stripe/stripe-public-key)
      (users/create-sysrev-stripe-customer! (get-user))
      (stripe/create-subscription-user! (get-user))
      (nav/log-in)
      (wait-until-stripe-id email)
      ;; after registering, does the stripe customer exist?
      (is (= email (:email (get-customer))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email stripe/default-plan)
      (is (= stripe/default-plan (get-stripe-plan)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= stripe/default-plan (get-db-plan)))
;;; upgrade plan
      (b/click "#user-name-link")
      (b/click "#user-billing")
      (b/click ".button.nav-plans.subscribe" :displayed? true)
      (b/click "a.payment-method.add-method")
;;; payment method
      ;; wait until a card number is available for input
      (b/wait-until-exists (label-input "Card Number"))
      ;; just try to 'Use Card', do we have all the error messages we would expect?
      (b/click use-card)
      ;; incomplete fields are shown
      (is (and (b/exists? (error-msg-xpath bstripe/incomplete-card-number-error))
               (b/exists? (error-msg-xpath bstripe/incomplete-expiration-date-error)
                          :wait? false)
               (b/exists? (error-msg-xpath bstripe/incomplete-security-code-error)
                          :wait? false)))
      (if (test/full-tests?)
        (log/info "running full stripe tests")
        (log/info "skipping full stripe tests"))
      (when (test/full-tests?)
        ;; basic failure with Luhn Check
        #_ (b/input-text (label-input "Card Number") bstripe/fail-luhn-check-cc)
        (bstripe/enter-cc-number bstripe/fail-luhn-check-cc)
        ;; error message displayed?
        (is (b/exists? (error-msg-xpath bstripe/invalid-card-number-error)))
        ;; 'Use Card' button disabled?
        (is (b/exists? ".button.use-card.disabled"))
        ;; cvc check fail
        (bstripe/enter-cc-information {:cardnumber bstripe/cvc-check-fail-cc
                                       :exp-date "0121"
                                       :cvc "123"
                                       :postal "11111"})
        (b/click use-card)
        (is (b/exists? (error-msg-xpath bstripe/invalid-security-code-error)))
        ;; card-declined-cc
        (bstripe/enter-cc-number bstripe/card-declined-cc)
        (b/click use-card)
        (is (b/exists? (error-msg-xpath bstripe/card-declined-error)))

        ;; incorrect-cvc-cc
        (bstripe/enter-cc-number bstripe/incorrect-cvc-cc)
        (b/click use-card)
        (is (b/exists? (error-msg-xpath bstripe/invalid-security-code-error)))

        ;; expired-card-cc
        (bstripe/enter-cc-number bstripe/expired-card-cc)
        (b/click use-card)
        (is (b/exists? (error-msg-xpath bstripe/card-expired-error)))

        ;; processing-error-cc
        (bstripe/enter-cc-number bstripe/processing-error-cc)
        (b/click use-card)
        (is (b/exists? (error-msg-xpath bstripe/card-processing-error)))
;;; attach-success-charge-fail-cc
        ;; in this case, the card is attached to the customer
        ;; but they won't be able to subscribe because the card doesn't go
        ;; through
        (bstripe/enter-cc-number bstripe/attach-success-charge-fail-cc)
        (b/click use-card)
        (b/click ".button.upgrade-plan")
        ;; check for the declined card message
        (is (b/exists? {:xpath "//p[contains(text(),'Your card was declined.')]"}))
;;; let's update our payment information (again) with a fraudulent card
        (b/click "a.payment-method.change-method")
        (b/wait-until-displayed (b/not-disabled use-card))
        (bstripe/enter-cc-information
         {:cardnumber bstripe/highest-risk-fraudulent-cc
          :exp-date "0121"
          :cvc "123"
          :postal "11111"})
        (b/click use-card)
        ;; try to subscribe again
        (b/click ".button.upgrade-plan")
        ;; card was declined
        (is (b/exists? {:xpath "//p[contains(text(),'Your card was declined.')]"}))
        (b/click "a.payment-method.change-method")
        (b/wait-until-displayed (b/not-disabled use-card)))
;;; finally, update with a valid cc number and see if we can subscribe
;;; to plans
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc
                                     :exp-date "0121"
                                     :cvc "123"
                                     :postal "11111"})
      (b/click use-card)
      ;; try to subscribe again
      (b/click ".button.upgrade-plan")
      ;; this time is goes through, confirm we are subscribed to the
      ;; Unlimied plan now
      (b/wait-until-displayed ".button.nav-plans.unsubscribe" 10000)
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
      (is (= stripe/default-plan (get-db-plan)))
      :clean-up
      (users/delete-sysrev-stripe-customer! (users/get-user-by-email email))))
