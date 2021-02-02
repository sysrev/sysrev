(ns sysrev.test.etaoin.account
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [clojure.tools.logging :as log]
            [etaoin.api :as etaoin]
            [etaoin.keys :refer [backspace with-alt]]
            [sysrev.test.etaoin.core :refer [*driver* click go]]
            [sysrev.util :as util]))

(def login-link "//a[@id='log-in-link']")
(def default-email "test@example.com")
(def default-password "testexample")
(def valid-cc {:cc "4242424242424242"
               :exp "1250"
               :cvc "123"})
(def unlimited-billing-text "Unlimited public and private projects")
(def unlimited-billing-li (str "//li[contains(text(),'" unlimited-billing-text "')]"))
(def basic-billing-text "Unlimited public projects")
(def basic-billing-li (str "//li[contains(text(),'"
                           basic-billing-text "')]"))
(def upgrade-plan-button "//button[contains(text(),'Upgrade Plan')]")
(def unsubscribe-button "//button[contains(text(),'Unsubscribe')]")

(defn create-account [& {:keys [email password]
                         :or {email default-email
                              password default-password}}]
  (let [[name domain] (str/split email #"@")
        email (format "%s+%s@%s" name (util/random-id) domain)]
    (go @*driver* "/register")
    (etaoin/wait-exists @*driver* :login-email-input)
    (etaoin/fill-multi @*driver* {:login-email-input email
                                  :login-password-input password})
    (etaoin/click @*driver* "//button[contains(text(),'Register')]")
    (etaoin/wait-exists @*driver* :new-project)
    {:email email :password password}))


(defn login [{:keys [email password]}]
  (go @*driver* "/login")
  (click @*driver* {:css "#log-in-link"})
  (etaoin/wait-exists @*driver* :login-email-input)
  (etaoin/fill-multi @*driver* {:login-email-input email
                                :login-password-input password})
  (etaoin/click @*driver* "//button[contains(text(),'Log in')]"))


(defn get-stripe-iframe-names []
  (mapv #(etaoin/get-element-attr-el @*driver* % :name)
        (etaoin/query-all @*driver* "//iframe")))

(defn clear-stripe-input [q]
  (dotimes [_ 5]
    (etaoin/fill @*driver* q (with-alt backspace))))

(defn enter-payment-information [{:keys [cc exp cvc]}]
  (etaoin/wait-visible @*driver* "//form[contains(@class,'StripeForm')]")
  (let [stripe-iframes (get-stripe-iframe-names)
        nth-frame #(str "//iframe[@name='" (nth stripe-iframes %) "']")]
    ;; enter cc
    (clear-stripe-input (nth-frame 0))
    (etaoin/fill @*driver* (nth-frame 0) cc)
    ;; enter exp
    (clear-stripe-input (nth-frame 1))
    (etaoin/fill @*driver* (nth-frame 1) exp)
    ;; enter cvc
    (clear-stripe-input (nth-frame 2))
    (etaoin/fill @*driver* (nth-frame 2) cvc)
    ;; save payment information
    (click @*driver* "//button[contains(@class,'use-card')]")))

(defn change-user-plan
  [& {:keys [enter-payment-information?]
      :or {enter-payment-information? true}}]
  (go @*driver* "/user/plans")
  (etaoin/wait-exists @*driver* "//a[contains(text(),'Back to user settings')]")
  ;; are we subscribe or unsubscribing?
  (let [current-plan (cond (etaoin/exists? @*driver* "//h1[contains(text(),'Upgrade from Basic to Pro')]")
                           :basic
                           (etaoin/exists? @*driver* "//h1[contains(text(),'Unsubscribe from your plan')]")
                           :unlimited
                           :else false)]
    (when-not current-plan
      (log/error "Current plan status unknown"))
    ;; user has basic blan
    (when (= current-plan
             :basic)
      ;; do we need to enter payment information?
      (when (and enter-payment-information?
                 (not (etaoin/exists? @*driver* upgrade-plan-button)))
        (enter-payment-information valid-cc))
      ;; upgrade plan
      (click @*driver* upgrade-plan-button)
      (etaoin/wait-exists @*driver* unlimited-billing-li)
      (is (etaoin/exists? @*driver* unlimited-billing-li)))
    ;; user has unlimited plan
    (when (= current-plan :unlimited)
      (click @*driver* unsubscribe-button)
      (etaoin/wait-exists @*driver* basic-billing-li)
      (is (etaoin/exists? @*driver* basic-billing-li)))))
