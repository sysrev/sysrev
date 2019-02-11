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
            [sysrev.test.browser.stripe :as browser-stripe]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.stripe :as stripe]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn wait-until-stripe-id
  [email]
  (test/wait-until #(not (nil? (:email (stripe/execute-action
                                        (customers/get-customer
                                         (:stripe-id (users/get-user-by-email email)))))))))

(defn wait-until-plan
  [email plan]
  (test/wait-until #(= plan
                       (-> (stripe/execute-action
                            (customers/get-customer
                             (:stripe-id (users/get-user-by-email email))))
                           :subscriptions :data first :items :data first :plan :name))))

;; elements
(def update-payment-button {:xpath "//div[contains(@class,'button') and contains(text(),'Update Payment Information')]"})
(def use-card-disabled-button {:xpath "//button[contains(@class,'button') and contains(@class,'disabled') and contains(text(),'Use Card')]"})
(def private-projects-button {:xpath "//button[contains(text(),'Get private projects')]"})
(def add-a-payment-method-link {:xpath "//a[contains(text(),'Add a payment method')]"})
(def change-payment-method-link {:xpath "//a[contains(text(),'Change payment method')]"})
(def upgrade-plan-button {:xpath "//button[contains(text(),'Upgrade Plan') and not(contains(@class,'disabled'))]"})
(def unsubscribe-button {:xpath "//button[contains(text(),'Unsubscribe')]"})

(defn label-input
  "Given a label, return an xpath for its input"
  [label]
  {:xpath (str "//label[contains(text(),'" label "')]/descendant::input")})

(defn error-msg-xpath
  "return an xpath for a error message div with error-msg"
  [error-msg]
  {:xpath (str "//div[contains(@class,'red') and contains(text(),\"" error-msg "\")]")})

(defn subscribed-to?
  "Is the customer subscribed to plan-name?"
  [plan-name]
  (b/exists? {:xpath (str "//span[contains(text(),'" plan-name "')]/ancestor::div[contains(@class,'plan')]/descendant::div[contains(text(),'Subscribed')]")}))

;; need to disable sending emails in this test
(deftest-browser register-and-check-basic-plan-subscription
  (and (test/db-connected?)
       (not= :remote-test (-> env :profile)))
  [{:keys [email password]} b/test-login]
  (do (log/info "register-and-check-basic-plan-subscription")
      (b/delete-test-user)
      (Thread/sleep 200)
      (b/create-test-user email password)
      (users/create-sysrev-stripe-customer! (users/get-user-by-email email))
      (stripe/subscribe-customer! (users/get-user-by-email email) api/default-plan)
      ;;(nav/register-user email password)
      ;; after registering, does the stripe customer exist?
      (wait-until-stripe-id email)
      (is (= email
             (:email (stripe/execute-action
                      (customers/get-customer
                       (:stripe-id (users/get-user-by-email email)))))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email api/default-plan)
      (is (= api/default-plan
             (-> (stripe/execute-action
                  (customers/get-customer
                   (:stripe-id (users/get-user-by-email email))))
                 :subscriptions :data first :items :data first :plan :name)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= api/default-plan
             (:name (plans/get-current-plan (users/get-user-by-email email)))))
      ;; clean up
      #_(nav/log-out)
      (b/delete-test-user)
      ;; make sure this has occurred for the next test
      (test/wait-until #(nil? (users/get-user-by-email email)))))

;; need to disable sending emails in this test
(deftest-browser register-and-subscribe-to-paid-plans
  (and (test/db-connected?)
       (not= :remote-test (-> env :profile)))
  [{:keys [email password]} b/test-login]
  (do (log/info "register-and-subscribe-to-paid-plans")
      (b/delete-test-user)
      (Thread/sleep 200)
      (b/create-test-user email password)
      (users/create-sysrev-stripe-customer! (users/get-user-by-email email))
      (stripe/subscribe-customer! (users/get-user-by-email email) api/default-plan)
      ;;(nav/register-user email password)
      (nav/log-in email password)
      (assert stripe/stripe-secret-key)
      (assert stripe/stripe-public-key)
      (wait-until-stripe-id email)
      ;; after registering, does the stripe customer exist?
      (is (= email
             (:email (stripe/execute-action
                      (customers/get-customer
                       (:stripe-id (users/get-user-by-email email)))))))
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email api/default-plan)
      (is (= api/default-plan
             (-> (stripe/execute-action
                  (customers/get-customer
                   (:stripe-id (users/get-user-by-email email))))
                 :subscriptions :data first :items :data first :plan :name)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= api/default-plan
             (:name (plans/get-current-plan (users/get-user-by-email email)))))
;;; upgrade plan
      ;; go to billing
      (nav/go-route "/user/settings/billing")
      (b/wait-until-displayed private-projects-button)
      (b/click private-projects-button)
      ;; add a payment
      (b/wait-until-displayed add-a-payment-method-link)
      ;; click on it
      (b/click add-a-payment-method-link)
;;; payment method
      ;; wait until a card number is available for input
      (b/wait-until-exists (label-input "Card Number"))
      ;; just try to 'Use Card', do we have all the error messages we would expect?
      (b/click browser-stripe/use-card-button)
      ;; incomplete fields are shown
      (is (and (b/exists? (error-msg-xpath browser-stripe/incomplete-card-number-error))
               (b/exists? (error-msg-xpath browser-stripe/incomplete-expiration-date-error)
                          :wait? false)
               (b/exists? (error-msg-xpath browser-stripe/incomplete-security-code-error)
                          :wait? false)))
      (if (test/full-tests?)
        (log/info "running full stripe tests")
        (log/info "skipping full stripe tests"))
      (when (test/full-tests?)
        ;; basic failure with Luhn Check
        ;;(b/input-text (label-input "Card Number") browser/stripe fail-luhn-check-cc)
        (browser-stripe/enter-cc-number browser-stripe/fail-luhn-check-cc)
        ;; error message displayed?
        (is (b/exists? (error-msg-xpath browser-stripe/invalid-card-number-error)))
        ;; 'Use Card' button disabled?
        (is (b/exists? use-card-disabled-button))
        ;; cvc check fail
        (browser-stripe/enter-cc-information {:cardnumber browser-stripe/cvc-check-fail-cc
                                              :exp-date "0121"
                                              :cvc "123"
                                              :postal "11111"})
        (b/click browser-stripe/use-card-button)
        (is (b/exists? (error-msg-xpath browser-stripe/invalid-security-code-error)))
        ;; card-declined-cc
        (browser-stripe/enter-cc-number browser-stripe/card-declined-cc)
        (b/click browser-stripe/use-card-button)
        (is (b/exists? (error-msg-xpath browser-stripe/card-declined-error)))

        ;; incorrect-cvc-cc
        (browser-stripe/enter-cc-number browser-stripe/incorrect-cvc-cc)
        (b/click browser-stripe/use-card-button)
        (is (b/exists? (error-msg-xpath browser-stripe/invalid-security-code-error)))

        ;; expired-card-cc
        (browser-stripe/enter-cc-number browser-stripe/expired-card-cc)
        (b/click browser-stripe/use-card-button)
        (is (b/exists? (error-msg-xpath browser-stripe/card-expired-error)))

        ;; processing-error-cc
        (browser-stripe/enter-cc-number browser-stripe/processing-error-cc)
        (b/click browser-stripe/use-card-button)
        (is (b/exists? (error-msg-xpath browser-stripe/card-processing-error)))
;;; attach-success-charge-fail-cc
        ;; in this case, the card is attached to the customer
        ;; but they won't be able to subscribe because the card doesn't go
        ;; through
        (browser-stripe/enter-cc-number browser-stripe/attach-success-charge-fail-cc)
        (b/click browser-stripe/use-card-button)
        (b/click upgrade-plan-button)
        ;; check for the declined card message
        (is (b/exists? {:xpath "//p[contains(text(),'Your card was declined.')]"}))
;;; let's update our payment information (again) with a fraudulent card
        (b/click change-payment-method-link)
        (b/wait-until-displayed browser-stripe/use-card-button)
        (browser-stripe/enter-cc-information
         {:cardnumber browser-stripe/highest-risk-fraudulent-cc
          :exp-date "0121"
          :cvc "123"
          :postal "11111"})
        (b/click browser-stripe/use-card-button)
        ;; try to subscribe again
        (b/click upgrade-plan-button)
        ;; card was declined
        (is (b/exists? {:xpath "//p[contains(text(),'Your card was declined.')]"}))
        (b/click change-payment-method-link)
        (b/wait-until-displayed browser-stripe/use-card-button))
;;; finally, update with a valid cc number and see if we can subscribe
;;; to plans
      (browser-stripe/enter-cc-information {:cardnumber browser-stripe/valid-visa-cc
                                            :exp-date "0121"
                                            :cvc "123"
                                            :postal "11111"})
      (b/click browser-stripe/use-card-button)
      ;; try to subscribe again
      (b/click upgrade-plan-button)
      ;; this time is goes through, confirm we are subscribed to the
      ;; pro plan now
      (b/wait-until-displayed unsubscribe-button)
      ;; Let's check to see if our db thinks the customer is subscribed to the Unlimited
      (is (= "Unlimited"
             (:name (plans/get-current-plan (users/get-user-by-email email)))))
      ;; Let's check that stripe.com thinks the customer is subscribed to the Unlimited plan
      (is (= "Unlimited"
             (-> (stripe/execute-action
                  (customers/get-customer
                   (:stripe-id (users/get-user-by-email email))))
                 :subscriptions :data first :items :data first :plan :name)))
;;; Subscribe back down to the Basic Plan
      (b/click unsubscribe-button)
      (b/click unsubscribe-button)
      (b/wait-until-displayed private-projects-button)
      ;; does stripe think the customer is registered to a basic plan?
      (wait-until-plan email api/default-plan)
      (is (= api/default-plan
             (-> (stripe/execute-action
                  (customers/get-customer
                   (:stripe-id (users/get-user-by-email email))))
                 :subscriptions :data first :items :data first :plan :name)))
      ;; do we think the user is subscribed to a basic plan?
      (is (= api/default-plan
             (:name (plans/get-current-plan (users/get-user-by-email email)))))
      #_ (nav/log-out)))
