(ns sysrev.test.browser.stripe
  (:require [clj-webdriver.taxi :as taxi]
            [sysrev.test.browser.core :as b]
            [clojure.tools.logging :as log]))

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
(def no-payment-method "You must provide a valid payment method")

(def cardnumber-input-iframe {:xpath "//iframe[@name='__privateStripeFrame4']"})
(def cardnumber-input "input[name~='cardnumber']")

(defn get-stripe-frame-names []
  (->> (b/current-frame-names)
       (filter #(re-matches #".*StripeFrame.*" %))))

(defn enter-cc-number
  [cc-number]
  (taxi/switch-to-default)
  (b/wait-until-displayed {:xpath "//h1[text()='Enter your Payment Method']"})
  ;; switch the the proper iframe. note that the name could change if stripe updates their library
  (taxi/switch-to-frame {:xpath (str "//iframe[@name='" (nth (get-stripe-frame-names) 0) "']")})
  (b/backspace-clear 30 cardnumber-input)
  ;; clear anything that could be in the form
  (b/set-input-text-per-char cardnumber-input cc-number)
  ;; switch back to default
  (taxi/switch-to-default))

(defn enter-cc-information
  [{:keys [cardnumber exp-date cvc postal]}]
  (log/info "entering stripe card information")
  (let [ ;; note: stripe could change the frame names
        frame-names (get-stripe-frame-names)
        exp-date-iframe {:xpath (str "//iframe[@name='" (nth frame-names 1) "']")}
        exp-date-input "input[name~='exp-date']"
        cvc-iframe {:xpath (str "//iframe[@name='" (nth frame-names 2) "']")}
        cvc-input "input[name~='cvc']"
        postal-iframe {:xpath (str "//iframe[@name='" (nth frame-names 3) "']")}
        postal-input "input[name~='postal']"]
    ;; let's reset to be sure we are in the default iframe
    (taxi/switch-to-default)
    (enter-cc-number cardnumber)
    ;; switch to month input iframe
    (taxi/switch-to-frame exp-date-iframe)
    (b/set-input-text-per-char exp-date-input exp-date)
    ;; swtich back to default
    (taxi/switch-to-default)
    ;; switch to cvc iframe
    (taxi/switch-to-frame cvc-iframe)
    (b/set-input-text-per-char cvc-input cvc)
    ;; switch back to default frame
    (taxi/switch-to-default)
    ;; switch to post code frame
    (taxi/switch-to-frame postal-iframe)
    (b/set-input-text-per-char postal-input postal)
    ;; we're done, return back to default
    (taxi/switch-to-default)
    (log/info "finished entering stripe card")
    (Thread/sleep 100)))
