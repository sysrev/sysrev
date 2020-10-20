(ns sysrev.test.browser.stripe
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.tools.logging :as log]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :refer [xpath]]))

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
#_(def attach-success-charge-fail-cc "4000000000000341")
#_(def highest-risk-fraudulent-cc "4100000000000019")

;; 3D Secure 2
(def three-d-secure-successful "4000000000003220")
(def three-d-secure-card-declined "4000008400001629")

;; error messages
(def invalid-card-number-error "Your card number is invalid")
(def incomplete-card-number-error "Your card number is incomplete")
(def incomplete-expiration-date-error "Your card's expiration date is incomplete")
(def incomplete-security-code-error "Your card's security code is incomplete")
(def invalid-security-code-error "Your card's security code is incorrect")
(def card-declined-error "Your card was declined")
(def card-expired-error "Your card has expired")
(def card-processing-error "An error occurred while processing your card. Try again in a little bit")
(def cardnumber-input (xpath "//input[@name='cardnumber']"))

(defn get-stripe-frame-names []
  (->> (b/current-frame-names)
       (filter #(re-matches #".*StripeFrame.*" %))))

(defn enter-cc-number
  [cc-number]
  (b/wait-until-loading-completes :timeout 15000 :interval 100)
  (taxi/switch-to-default)
  (b/wait-until-displayed {:xpath "//form[contains(@class,'StripeForm')]"})
  ;; switch the the proper iframe. note that the name could change if stripe updates their library
  (taxi/switch-to-frame
   (taxi/element {:xpath (format "//iframe[@name='%s']"
                                 (nth (get-stripe-frame-names) 0))}))
  ;;(taxi/click cardnumber-input)
  ;; clear anything that could be in the form
  (b/clear cardnumber-input)
  ;;(b/backspace-clear 20 cardnumber-input)
  ;; set the input
  (b/set-input-text-per-char cardnumber-input cc-number)
  ;; switch back to default
  (taxi/switch-to-default))

(defn enter-cc-information [{:keys [cardnumber exp-date cvc]
                             :or {exp-date "0130" cvc "123"}}]
  (log/info "entering stripe card information")
  (taxi/switch-to-default)
  (b/wait-until-displayed {:xpath "//form[contains(@class,'StripeForm')]"})
  (let [ ;; note: stripe could change the frame names
        _ (b/wait-until #(>= (count (get-stripe-frame-names)) 3))
        frame-names (get-stripe-frame-names)
        exp-date-iframe {:xpath (str "//iframe[@name='" (nth frame-names 1) "']")}
        exp-date-input "input[name~='exp-date']"
        cvc-iframe {:xpath (str "//iframe[@name='" (nth frame-names 2) "']")}
        cvc-input "input[name~='cvc']"]
    ;; let's reset to be sure we are in the default iframe
    (enter-cc-number cardnumber)
    ;; switch to month input iframe
    (taxi/switch-to-frame (taxi/element exp-date-iframe))
    (b/set-input-text-per-char exp-date-input exp-date)
    ;; swtich back to default
    (taxi/switch-to-default)
    ;; switch to cvc iframe
    (taxi/switch-to-frame (taxi/element cvc-iframe))
    (b/set-input-text-per-char cvc-input cvc)
    ;; switch back to default frame
    (taxi/switch-to-default)
    ;; we're done, return back to default
    (taxi/switch-to-default)
    (log/info "finished entering stripe card")
    (Thread/sleep 75)))
