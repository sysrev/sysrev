(ns sysrev.test.etaoin.account
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [clojure.tools.logging :as log]
            [etaoin.api :as ea]
            [etaoin.keys :refer [backspace with-alt]]
            [sysrev.test.etaoin.core :as e :refer [*driver*]]
            [sysrev.util :as util]))

(def default-email "test@example.com")
(def default-password "testexample")

(def valid-cc {:cc "4242424242424242" :exp "1250" :cvc "123"})

(def basic-billing-li     (format "//li[contains(text(),'%s')]"
                                  "Unlimited public projects"))
(def unlimited-billing-li (format "//li[contains(text(),'%s')]"
                                  "Unlimited public and private projects"))
(def upgrade-plan-button "//button[contains(text(),'Upgrade Plan')]")
(def unsubscribe-button "//button[contains(text(),'Unsubscribe')]")

(defn create-account [& {:keys [email password]
                         :or {email default-email
                              password default-password}}]
  (let [[name domain] (str/split email #"@")
        email (format "%s+%s@%s" name (util/random-id) domain)]
    (e/go "/register")
    (e/wait-exists :login-email-input)
    (ea/fill-multi @*driver* {:login-email-input email
                              :login-password-input password})
    (e/click "//button[contains(text(),'Register')]")
    (e/wait-exists :new-project)
    {:email email :password password}))

(defn log-out [& {:keys [silent]}]
  (when (e/exists? :log-out-link, :wait false)
    (when-not silent (log/info "logging out"))
    (e/wait-loading :pre-wait true)
    (e/click :log-out-link, :if-not-exists :skip)
    (e/wait-loading :pre-wait true)))

(defn log-in [{:keys [email password]}]
  (log/info "logging in" (str "(" email ")"))
  (e/go "/" :silent true)
  (log-out :silent true)
  (e/click :log-in-link)
  (e/wait-exists :login-email-input)
  (ea/fill-multi @*driver* {:login-email-input email
                            :login-password-input password})
  (e/click {:css "button[name='submit']"})
  (e/wait-loading :pre-wait 40 :loop 2)
  (e/go "/" :silent true)
  (e/wait-loading :pre-wait 40 :loop 2))

(defn get-stripe-iframe-names []
  (mapv #(ea/get-element-attr-el @*driver* % :name)
        (ea/query-all @*driver* "//iframe")))

(defn clear-stripe-input [q]
  (dotimes [_ 5]
    (e/fill q (with-alt backspace))))

(defn set-stripe-input [q text]
  (ea/wait-enabled @*driver* q)
  (clear-stripe-input q)
  (e/fill q text))

(defn enter-payment-information [{:keys [cc exp cvc]}]
  (ea/wait-visible @*driver* {:css "form.StripeForm"})
  (e/wait-loading :pre-wait 100)
  (let [stripe-iframes (get-stripe-iframe-names)
        nth-frame #(format "//iframe[@name='%s']" (nth stripe-iframes %))]
    (set-stripe-input (nth-frame 0) cc)
    (set-stripe-input (nth-frame 1) exp)
    (set-stripe-input (nth-frame 2) cvc)
    (e/click {:css "button.use-card"})))

(defn change-user-plan [& {:keys [enter-payment-information?]
                           :or {enter-payment-information? true}}]
  (e/go "/user/plans")
  (e/wait-exists "//a[contains(text(),'Back to user settings')]")
  ;; are we subscribe or unsubscribing?
  (cond (e/exists? "//h1[contains(text(),'Upgrade from Basic to Pro')]" :wait false)
        ;; currently on basic plan
        (do (when (and enter-payment-information?
                       (not (e/exists? upgrade-plan-button :wait false)))
              ;; enter payment information if needed
              (enter-payment-information valid-cc))
            (e/click upgrade-plan-button)     ; upgrade plan
            (is (e/exists? unlimited-billing-li)))
        (e/exists? "//h1[contains(text(),'Unsubscribe from your plan')]" :wait false)
        ;; currently on unlimited plan
        (do (e/click unsubscribe-button)
            (is (e/exists? basic-billing-li)))
        :else
        (throw (ex-info "Current plan status unknown" {}))))
