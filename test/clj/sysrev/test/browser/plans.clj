(ns sysrev.test.browser.plans
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [sysrev.payment.plans :as plans]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.payment.stripe :as stripe]
            [sysrev.util :as util]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def use-card "form.StripeForm .ui.button.use-card")
(def upgrade-link (xpath "//a[text()='Upgrade']"))
(def back-to-user-settings (xpath "//a[contains(text(),'Back to user settings')]"))

;; pricing workflow elements
(def choose-pro-button (xpath "//a[contains(text(),'Choose Premium')]"))
(def create-account (xpath "//h3[contains(text(),'Create a free account to upgrade to Premium Plan')]"))
(def upgrade-plan (xpath "//h1[contains(text(),'Upgrade from Basic to Premium')]"))
(def pricing-link (xpath "//a[@id='pricing-link']"))

(defn click-use-card [& {:keys [wait delay]
                         :or {wait true delay 50 error? false}}]
  (b/wait-until-loading-completes :timeout 10000 :interval 100)
  (b/click use-card :displayed? true :delay 200 :timeout 15000)
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
  (some-> (user-by-email email :stripe-id) (stripe/get-customer)))

(defn customer-plan [customer]
  (some-> customer :subscriptions :data first :items :data first :plan))

(defn user-stripe-plan [email]
  (some-> (get-user-customer email) (customer-plan) :nickname))

(defn user-db-plan [email]
  (some-> (user-by-email email :user-id) (plans/user-current-plan) :nickname))

(defn wait-until-stripe-id
  "Wait until stripe has customer entry for email."
  [email]
  (test/wait-until #(:email (get-user-customer email)) 7500 100))

(defn wait-until-plan
  "Wait until stripe customer entry matches plan value."
  [email plan]
  (test/wait-until #(= plan (user-stripe-plan email)) 7500 100))

(defn label-input
  "Given a label, return an xpath for its input"
  [label]
  (xpath "//label[contains(text(),'" label "')]/descendant::input"))

(defn error-msg-xpath
  "return an xpath for a error message div with error-msg"
  [error-msg]
  (xpath "//div[contains(@class,'red') and contains(text(),\"" error-msg "\")]"))

(defn user-subscribe-to-unlimited
  [email & [password]]
  (when-not (get-user-customer email)
    (log/info (str "Stripe Customer created for " email))
    (user/create-user-stripe-customer! (user-by-email email)))
  (wait-until-stripe-id email)
  (stripe/create-subscription-user! (user-by-email email))
  (Thread/sleep 100)
  (nav/log-in email password)
  ;; go to plans
  (b/click "#user-name-link")
  (b/click "#user-billing")
  (b/click ".button.nav-plans.subscribe" :displayed? true)
  ;; enter payment information
  (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
  (click-use-card)
  ;; upgrade to unlimited
  (click-upgrade-plan)
  ;; this time is goes through, confirm we are subscribed to the
  ;; Unlimited plan now
  (b/wait-until-displayed ".button.nav-plans.unsubscribe")
  (log/info "found \"Unsubscribe\" button"))

;; need to disable sending emails in this test (for register user via web)
(deftest-browser register-and-check-basic-plan-subscription
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [{:keys [email]} test-user
   get-test-user #(user-by-email email)
   get-customer #(get-user-customer email)]
  (do (user/create-user-stripe-customer! (get-test-user))
      (stripe/create-subscription-user! (get-test-user))
      ;; after registering, does the stripe customer exist?
      (wait-until-stripe-id email)
      (is (= email (:email (get-customer))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email plans-info/default-plan)
      (is (= plans-info/default-plan (user-stripe-plan email)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= plans-info/default-plan (user-db-plan email))))
  :cleanup (b/cleanup-test-user! :email email))

;; need to disable sending emails in this test
(deftest-browser register-and-subscribe-to-paid-plans
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [{:keys [email]} test-user
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
      (wait-until-plan email plans-info/default-plan)
      (is (= plans-info/default-plan (get-stripe-plan)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= plans-info/default-plan (get-db-plan)))
      (nav/log-in email)
;;; upgrade plan
      (b/click "#user-name-link")
      (b/click "#user-billing")
      (b/click ".button.nav-plans.subscribe" :displayed? true)
      #_(b/click "a.payment-method.add-method")
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
          (when (b/try-wait b/wait-until #(taxi/exists? (b/not-disabled use-card)) 500 30)
            (click-use-card))
          (when error
            (when (string? error)
              (b/is-soon (taxi/exists? (error-msg-xpath error))))
            #_ (b/is-soon (taxi/exists? (str use-card ".disabled"))))
          (when declined
            (b/wait-until-loading-completes :pre-wait 100)
            (click-upgrade-plan)
            (b/is-soon
             (taxi/exists? {:xpath "//p[contains(text(),'Your card was declined.')]"}))
            (b/click "a.payment-method.change-method")
            (b/wait-until-displayed (b/not-disabled use-card)))))
;;; finally, update with a valid cc number and see if we can subscribe to plans
      (log/info "testing valid card info")
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
      (click-use-card)
      ;; try to subscribe again
      (click-upgrade-plan)
      ;; this time is goes through, confirm we are subscribed to the
      ;; Unlimited plan now
      (b/wait-until-displayed ".button.nav-plans.unsubscribe")
      (log/info "found \"Unsubscribe\" button")
      ;; Let's check to see if our db thinks the customer is subscribed to the Unlimited
      (is (= plans-info/unlimited-user (get-db-plan)))
      ;; Let's check that stripe.com thinks the customer is subscribed to the Unlimited plan
      (is (= plans-info/unlimited-user (get-stripe-plan)))
;;; Subscribe back down to the Basic Plan
      (b/click ".button.nav-plans.unsubscribe")
      (b/click ".button.unsubscribe-plan")
      (b/click ".button.nav-plans.subscribe" :displayed? true)
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email plans-info/default-plan)
      (is (= plans-info/default-plan (get-stripe-plan)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= plans-info/default-plan (get-db-plan)))))

(deftest-browser subscribe-to-unlimited-through-pricing-no-account
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [email (format "baz+%s@qux.com" (util/random-id))
   password "bazqux"
   get-test-user #(user-by-email email)
   get-customer #(get-user-customer email)
   get-db-plan #(user-db-plan email)]
  (do
    (nav/go-route "/pricing")
    (taxi/execute-script "window.scrollTo(0,document.body.scrollHeight);")
    (b/wait-until-displayed choose-pro-button)
    (b/click choose-pro-button)
    ;; register
    (b/wait-until-displayed create-account)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    ;; upgrade plan
    (b/wait-until-displayed upgrade-plan)
    (is (= "Basic" (get-db-plan)))
    ;; update payment method
    (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
    (click-use-card)
    (click-upgrade-plan)
    ;; we have an unlimited plan
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (is (= plans-info/unlimited-user (get-db-plan))))
  :cleanup (b/cleanup-test-user! :email email))

;; the user changes their mind in the filling
;; out information but eventually does sign up anyway
;; through the pricing workflow
(deftest-browser subscribe-to-unlimited-through-pricing-cold-feet
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [email (format "baz+%s@qux.com" (util/random-id))
   password "bazqux"
   get-test-user #(user-by-email email)
   get-customer #(get-user-customer email)
   get-db-plan #(user-db-plan email)]
  (do
    (nav/go-route "/pricing")
    (taxi/execute-script "window.scrollTo(0,document.body.scrollHeight);")
    (b/wait-until-displayed choose-pro-button)
    (b/click choose-pro-button)
    ;; register
    (b/wait-until-displayed create-account)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    ;; go to upgrade plan
    (b/wait-until-displayed upgrade-plan)
    ;; refresh to make sure state isn't an issue
    (taxi/refresh)
    (b/wait-until-displayed upgrade-plan)
    ;; got cold feet, decides against upgrading
    (b/click back-to-user-settings)
    (is (= "Basic" (get-db-plan)))
    ;; go back to main page and go through pricing
    (nav/go-route "/")
    ;; click on pricing
    (b/click pricing-link)
    (b/wait-until-displayed choose-pro-button)
    (b/click choose-pro-button)
    ;; update payment method
    (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
    (click-use-card)
    (click-upgrade-plan)
    ;; we have an unlimited plan
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (is (= plans-info/unlimited-user (get-db-plan))))
  :cleanup (b/cleanup-test-user! :email email))

(deftest-browser subscribe-to-unlimited-annual-through-pricing-no-account
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [email (format "baz+%s@example.com" (util/random-id))
   password "bazqux"
   get-test-user #(user-by-email email)
   get-customer #(get-user-customer email)
   get-db-plan #(user-db-plan email)]
  (do
    (nav/go-route "/pricing")
    (taxi/execute-script "window.scrollTo(0,document.body.scrollHeight);")
    (b/wait-until-displayed choose-pro-button)
    (b/click choose-pro-button)
    ;; register
    (b/wait-until-displayed create-account)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    ;; upgrade plan
    (b/wait-until-displayed upgrade-plan)
    (is (= "Basic" (get-db-plan)))
    ;; pay yearly
    (b/click (xpath "//label[contains(text(),'Pay Yearly')]"))
    (b/wait-until-displayed (xpath "//h3[contains(text(),'$360.00 / year')]"))
    ;; update payment method
    (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
    (click-use-card)
    (click-upgrade-plan)
    ;; we have an unlimited plan
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (is (= plans-info/unlimited-user-annual (get-db-plan))))
  :cleanup (b/cleanup-test-user! :email email))
