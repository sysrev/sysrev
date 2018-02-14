(ns sysrev.test.browser.plans
  (:require [sysrev.test.browser.core :as browser]))


;; valid number
(def valid-visa-number "4242424242424242")

;; these will fail payment method is updated
;; will return error messages from the server, sent by stripe.com
(def cvc-check-fail "4000000000000101")
(def card-declined "4000000000000002")
(def incorrect-cvc "4000000000000127")
(def expired-card "4000000000000069")
(def processing-error "4000000000000119")

;; this should be handled in the form
(def incorret-number "4242424242424241") ; fails Luhn Check

;; these fail after payment method is updated
(def attach-success-charge-fail "4000000000000341")
(def highest-risk-fraudulent "4100000000000019")


