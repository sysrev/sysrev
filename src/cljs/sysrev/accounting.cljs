(ns sysrev.accounting
  (:require [cljsjs.accounting]))

;; functions around accounting.js
(defn unformat
  "Converts a string to a currency amount (default is in dollar)"
  [string]
  ($ js/accounting unformat string))

(defn to-fixed
  "Converts a number to a fixed value string to n decimal places"
  [number n]
  ($ js/accounting toFixed number n))

(defn string->cents
  "Convert a string to a number in cents"
  [string]
  (-> (to-fixed string 2)
      unformat
      (* 100)))

(defn cents->string
  "Convert a number to a USD currency string"
  [number]
  ($ js/accounting formatMoney (/ number 100)))

