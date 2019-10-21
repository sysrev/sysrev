(ns sysrev.accounting
  (:require ["accounting" :as accounting]
            [reagent.interop :refer-macros [$]]))

;; https://stackoverflow.com/questions/2227370/currency-validation
(def valid-usd-regex
  #"^[\$]?[0-9]\d*(((,\d{3}){1})?(\.\d{0,2})?)$")

;; functions around accounting.js
(defn unformat
  "Converts a string to a currency amount (default is in dollar)"
  [string]
  (accounting/unformat string))

(defn to-fixed
  "Converts a number to a fixed value string to n decimal places"
  [number n]
  (accounting/toFixed number n))

(defn string->cents
  "Convert a string to a number in cents"
  [string]
  (-> string (unformat) (* 100) (Math/round)))

(defn cents->string
  "Convert a number to a USD currency string"
  [number]
  ;; accounting.js puts a - sign INSIDE of the amount e.g. $-9.50
  (str (when (neg? number) "-")
       (accounting/formatMoney (/ (if (neg? number)
                                    (- number)
                                    number) 100))))

(defn format-money [amount unit]
  (accounting/formatMoney amount unit))
