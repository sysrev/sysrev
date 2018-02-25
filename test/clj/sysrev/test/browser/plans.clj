(ns sysrev.test.browser.plans
  (:require [clj-stripe.customers :as customers]
            [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [sysrev.api :as api]
            [sysrev.db.plans :as plans]
            [sysrev.db.users :as users]
            [sysrev.test.core :refer [default-fixture wait-until]]
            [sysrev.test.browser.core :as browser]
            [sysrev.test.browser.navigate :as navigate]
            [sysrev.stripe :as stripe]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

;; for manual testing purposes, this is handy:
;; (do (stripe/unsubscribe-customer! (users/get-user-by-email "foo@bar.com")) (stripe/delete-customer! (users/get-user-by-email "foo@bar.com")) (users/delete-user (:user-id (users/get-user-by-email "foo@bar.com"))))

;; valid number
(def valid-visa-cc "4242424242424242")

;; these will fail payment method is updated
;; will return error messages from the server, sent by stripe.com
(def cvc-check-fail-cc "4000000000000101")
(def card-declined-cc "4000000000000002")
(def incorrect-cvc-cc "4000000000000127")
(def expired-card-cc "4000000000000069")
(def processing-error-cc "4000000000000119")

;; this should be handled in the form
(def fail-luhn-check-cc "4242424242424241") ; fails Luhn Check

;; these fail after payment method is updated
(def attach-success-charge-fail-cc "4000000000000341")
(def highest-risk-fraudulent-cc "4100000000000019")

;; error messages
(def no-payment-method-error "You must enter a valid payment method before subscribing to this plan")
(def invalid-card-number-error "Your card number is invalid")
(def incomplete-card-number-error "Your card number is incomplete")
(def incomplete-expiration-date-error "Your card's expiration date is incomplete")
(def incomplete-security-code-error "Your card's security code is incomplete")
(def invalid-security-code-error "Your card's security code is incorrect")
(def card-declined-error "Your card was declined")
(def card-expired-error "Your card has expired")
(def card-processing-error "An error occurred while processing your card. Try again in a little bit")

(deftest register-and-check-basic-plan-subscription
  (let [{:keys [email password]} browser/test-login]
    (browser/delete-test-user)
    (navigate/register-user email password)
    (browser/wait-until-loading-completes)
    ;; after registering, does the stripe customer exist?
    (is (= email
           (:email (stripe/execute-action
                    (customers/get-customer
                     (:stripe-id (users/get-user-by-email email)))))))
    ;; does stripe think the customer is registered to a basic plan?
    (is (= api/default-plan
           (-> (stripe/execute-action
                (customers/get-customer
                 (:stripe-id (users/get-user-by-email email))))
               :subscriptions :data first :items :data first :plan :name)))
    ;; do we think the user is subscribed to a basic plan?
    (is (= api/default-plan
           (:name (plans/get-current-plan (users/get-user-by-email email)))))
    ;; clean up
    (let [user (users/get-user-by-email email)]
      (users/delete-user (:user-id user))
      (is (:deleted (stripe/delete-customer! user)))
      ;; make sure this has occurred for the next test
      (wait-until #(nil? (users/get-user-by-email email))))))

;; elements
(def basic-plan-div {:xpath "//span[contains(text(),'Basic')]/ancestor::div[contains(@class,'plan')]"})
(def subscribe-button {:xpath "//div[contains(@class,'button') and contains(text(),'Subscribe')]"})
(def update-payment-button {:xpath "//div[contains(@class,'button') and contains(text(),'Update Payment Information')]"})
(def use-card-button {:xpath "//button[contains(@class,'button') and contains(text(),'Use Card') and not(contains(@class,'disabled'))]"})
(def use-card-disabled-button {:xpath "//button[contains(@class,'button') and contains(@class,'disabled') and contains(text(),'Use Card')]"})

;; based on: https://crossclj.info/ns/io.aviso/taxi-toolkit/0.3.1/io.aviso.taxi-toolkit.ui.html#_clear-with-backspace
(def backspace-clear-length 30)
;; might be worth it to pull this library in at some point
(defn backspace-clear
  "Hit backspace in input-element length times. Always returns true"
  [length input-element]
  (doall (repeatedly length
                     #(do (taxi/send-keys input-element org.openqa.selenium.Keys/BACK_SPACE))))
  true)

(defn label-input
  "Given a label, return an xpath for its input"
  [label]
  {:xpath (str "//label[contains(text(),'" label "')]/descendant::input")})

(defn error-msg-xpath
  "return an xpath for a error message div with error-msg"
  [error-msg]
  {:xpath (str "//div[contains(@class,'red') and contains(text(),\"" error-msg "\")]")})

(defn select-plan
  "Click the 'Select Plan' button of plan with plan-name"
  [plan-name]
  (taxi/click {:xpath (str "//span[contains(text(),'" plan-name "')]/ancestor::div[contains(@class,'plan')]/descendant::div[contains(@class,'button')]")}))

(defn subscribed-to?
  "Is the customer subscribed to plan-name?"
  [plan-name]
  (taxi/exists? {:xpath (str "//span[contains(text(),'" plan-name "')]/ancestor::div[contains(@class,'plan')]/descendant::div[contains(text(),'Subscribed')]")}))

(deftest register-and-subscribe-to-paid-plans
  (let [{:keys [email password]} browser/test-login]
    (browser/delete-test-user)
    (navigate/register-user email password)
    (browser/wait-until-loading-completes)
    (assert stripe/stripe-secret-key)
    (assert stripe/stripe-public-key)
    ;; after registering, does the stripe customer exist?
    (is (= email
           (:email (stripe/execute-action
                    (customers/get-customer
                     (:stripe-id (users/get-user-by-email email)))))))
    ;; does stripe think the customer is registered to a basic plan?
    (is (= api/default-plan
           (-> (stripe/execute-action
                (customers/get-customer
                 (:stripe-id (users/get-user-by-email email))))
               :subscriptions :data first :items :data first :plan :name)))
    ;; do we think the user is subscribed to a basic plan?
    (is (= api/default-plan
           (:name (plans/get-current-plan (users/get-user-by-email email)))))
;;; plan selection
    ;; go to plans
    (browser/go-route "/plans")
    (browser/wait-until-displayed basic-plan-div)
    ;; select the 'Pro' plan
    (select-plan "Pro")
    ;; Click the subscribe button
    (browser/wait-until-displayed subscribe-button)
    (taxi/click subscribe-button)
    ;; No valid payment method
    (browser/wait-until-displayed (error-msg-xpath no-payment-method-error))
    (is (taxi/exists? (error-msg-xpath no-payment-method-error)))

;;; payment method
    ;; Let's update our payment method
    (taxi/click update-payment-button)
    ;; wait until a card number is available for input
    (browser/wait-until-exists (label-input "Card Number"))
    ;; just try to 'Use Card', do we have all the error messages we would expect?
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-exists (error-msg-xpath incomplete-card-number-error))
    ;; incomplete fields are shown
    (is (and (taxi/exists? (error-msg-xpath incomplete-card-number-error))
             (taxi/exists? (error-msg-xpath incomplete-expiration-date-error))
             (taxi/exists? (error-msg-xpath incomplete-security-code-error))))
    ;; basic failure with Luhn Check
    (taxi/input-text (label-input "Card Number") fail-luhn-check-cc)
    (browser/wait-until-displayed (error-msg-xpath invalid-card-number-error))
    ;; error message displayed?
    (is (taxi/exists? (error-msg-xpath invalid-card-number-error)))
    ;; 'Use Card' button disabled?
    (is (taxi/exists? use-card-disabled-button))

    ;; cvc-check-fail-cc
    ;; why so many backspaces? the exact amount needed, 16,
    ;; doesn't consistently clear the fields
    (backspace-clear backspace-clear-length (label-input "Card Number"))
    (taxi/input-text (label-input "Card Number") cvc-check-fail-cc)
    (taxi/input-text (label-input "Expiration date") "0120")
    (taxi/input-text (label-input "CVC") "123")
    (taxi/input-text (label-input "Postal code") "11111")
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-displayed (error-msg-xpath invalid-security-code-error))
    (is (taxi/exists? (error-msg-xpath invalid-security-code-error)))

    ;;  card-declined-cc
    (backspace-clear backspace-clear-length (label-input "Card Number"))
    (taxi/input-text (label-input "Card Number") card-declined-cc)
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-displayed (error-msg-xpath card-declined-error))
    (is (taxi/exists? (error-msg-xpath card-declined-error)))

    ;; incorrect-cvc-cc
    (backspace-clear backspace-clear-length (label-input "Card Number"))
    (taxi/input-text (label-input "Card Number") incorrect-cvc-cc)
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-displayed (error-msg-xpath invalid-security-code-error))
    (is (taxi/exists? (error-msg-xpath invalid-security-code-error)))

    ;; expired-card-cc
    (backspace-clear backspace-clear-length (label-input "Card Number"))
    (taxi/input-text (label-input "Card Number") expired-card-cc)
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-displayed (error-msg-xpath card-expired-error))
    (is (taxi/exists? (error-msg-xpath card-expired-error)))

    ;; processing-error-cc
    (backspace-clear backspace-clear-length (label-input "Card Number"))
    (taxi/input-text (label-input "Card Number") processing-error-cc)
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-displayed (error-msg-xpath card-processing-error))
    (is (taxi/exists? (error-msg-xpath card-processing-error)))

;;; attach-success-charge-fail-cc
    ;; in this case, the card is attached to the customer
    ;; but they won't be able to subscribe because the card doesn't go
    ;; through
    (backspace-clear backspace-clear-length (label-input "Card Number"))
    (taxi/input-text (label-input "Card Number") attach-success-charge-fail-cc)
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    (browser/wait-until-displayed subscribe-button)
    (taxi/click subscribe-button)
    ;; check for the declined card message
    (browser/wait-until-displayed (error-msg-xpath card-declined-error))
    (is (taxi/exists? (error-msg-xpath card-declined-error)))

;;; let's update our payment information (again) with a fraudulent card
    (taxi/click update-payment-button)
    (browser/wait-until-displayed use-card-button)
    (taxi/input-text (label-input "Card Number") highest-risk-fraudulent-cc)
    (taxi/input-text (label-input "Expiration date") "0120")
    (taxi/input-text (label-input "CVC") "123")
    (taxi/input-text (label-input "Postal code") "11111")
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    ;; try to subscribe again
    (browser/wait-until-displayed subscribe-button)
    (taxi/click subscribe-button)
    ;; card was declined
    (browser/wait-until-displayed (error-msg-xpath card-declined-error))
    (is (taxi/exists? (error-msg-xpath card-declined-error)))
;;; finally, update with a valid cc number and see if we can subscribe
;;; to plans!
    (taxi/click update-payment-button)
    (browser/wait-until-displayed use-card-button)
    (taxi/input-text (label-input "Card Number") valid-visa-cc)
    (taxi/input-text (label-input "Expiration date") "0120")
    (taxi/input-text (label-input "CVC") "123")
    (taxi/input-text (label-input "Postal code") "11111")
    (browser/wait-until-displayed use-card-button)
    (taxi/click use-card-button)
    ;; try to subscribe again
    (browser/wait-until-displayed subscribe-button)
    (taxi/click subscribe-button)
    ;; this time is goes through, confirm we are subscribed to the
    ;; pro plan now
    (browser/wait-until-displayed basic-plan-div)
    (subscribed-to? "Pro")
    ;; Let's check to see if our db thinks the customer is subscribed to the Pro plan
    (is (= "Pro"
           (:name (plans/get-current-plan (users/get-user-by-email email)))))
    ;; Let's check that stripe.com thinks the customer is subscribed to the Pro plan
    (is (= "Pro"
           (-> (stripe/execute-action
                (customers/get-customer
                 (:stripe-id (users/get-user-by-email email))))
               :subscriptions :data first :items :data first :plan :name)))

;;; Subscribe to the Premium plan
    (select-plan "Premium")
    (Thread/sleep 2000)
    ;; Click the subscribe button
    (browser/wait-until-exists subscribe-button)
    (taxi/click subscribe-button)
    (browser/wait-until-displayed basic-plan-div)
    (subscribed-to? "Premium")
    ;; Let's check to see if our db thinks the customer is subscribed to the Premium plan
    (is (= "Premium"
           (:name (plans/get-current-plan (users/get-user-by-email email)))))
    ;; Let's check that stripe.com thinks the customer is subscribed to the Premium plan
    (is (= "Premium"
           (-> (stripe/execute-action
                (customers/get-customer
                 (:stripe-id (users/get-user-by-email email))))
               :subscriptions :data first :items :data first :plan :name)))
;;; Subscribe back down to the Basic Plan
    (select-plan "Basic")
    (taxi/wait-until #(subscribed-to? "Basic")
                     10000)
    (is (subscribed-to? "Basic"))))
