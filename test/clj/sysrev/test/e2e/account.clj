(ns sysrev.test.e2e.account
  (:require
   [clojure.string :as str]
   [clojure.test :refer [is]]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [etaoin.keys :refer [backspace with-alt]]
   [sysrev.test.e2e.core :as e]
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

(defn create-account [{:keys [driver] :as test-resources}
                      & {:keys [email password]
                         :or {email default-email
                              password default-password}}]
  (let [[name domain] (str/split email #"@")
        email (format "%s+%s@%s" name (util/random-id) domain)]
    (e/go test-resources "/register")
    (doto driver
      (e/wait-exists :login-email-input)
      (ea/fill-multi {:login-email-input email
                      :login-password-input password})
      (e/click-visible "//button[contains(text(),'Register')]")
      (e/wait-exists :new-project))
    {:email email :password password}))

(defn log-out [driver & {:keys [silent]}]
  (when (e/exists? driver :log-out-link, :wait false)
    (when-not silent (log/info "logging out"))
    (doto driver
      (e/wait-loading :pre-wait true)
      (e/click-visible :log-out-link)
      (e/wait-loading :pre-wait true))))

(defn log-in
  ([{:keys [driver system]} {:keys [email password]}]
   (log/info "logging in" (str "(" email ")"))
   (doto driver
     (ea/go (e/absolute-url system "/login"))
     (ea/wait-exists :login-email-input)
     (ea/fill-multi {:login-email-input email
                     :login-password-input password})
     (e/click-visible {:css "button[name='submit']"})
     (ea/wait-visible {:fn/has-text "Your Projects"}))))

(defn get-stripe-iframe-names [driver]
  (mapv #(ea/get-element-attr-el driver % :name)
        (ea/query-all driver "//iframe")))

(defn clear-stripe-input [driver q]
  (dotimes [_ 5]
    (e/fill driver q (with-alt backspace))))

(defn set-stripe-input [driver q text]
  (ea/wait-enabled driver q)
  (clear-stripe-input driver q)
  (e/fill driver q text))

(defn enter-payment-information [driver {:keys [cc exp cvc]}]
  (ea/wait-visible driver {:css "form.StripeForm"})
  (e/wait-loading driver :pre-wait 100)
  (let [stripe-iframes (get-stripe-iframe-names driver)
        nth-frame #(format "//iframe[@name='%s']" (nth stripe-iframes %))]
    (doto driver
      (set-stripe-input (nth-frame 0) cc)
      (set-stripe-input (nth-frame 1) exp)
      (set-stripe-input (nth-frame 2) cvc)
      (e/click-visible {:css "button.use-card"}))))

(defn change-user-plan [{:keys [driver] :as test-resources}
                        & {:keys [enter-payment-information?]
                           :or {enter-payment-information? true}}]
  (e/go test-resources "/user/plans")
  (e/wait-exists driver "//a[contains(text(),'Back to user settings')]")
  ;; are we subscribe or unsubscribing?
  (cond (e/exists? driver "//h1[contains(text(),'Upgrade from Basic to Premium')]" :wait false)
        ;; currently on basic plan
        (do (when (and enter-payment-information?
                       (not (e/exists? driver upgrade-plan-button :wait false)))
              ;; enter payment information if needed
              (enter-payment-information driver valid-cc))
            (e/click-visible driver upgrade-plan-button)     ; upgrade plan
            (is (e/exists? driver unlimited-billing-li)))
        (e/exists? driver "//h1[contains(text(),'Unsubscribe from your plan')]" :wait false)
        ;; currently on unlimited plan
        (do (e/click-visible driver unsubscribe-button)
            (is (e/exists? driver basic-billing-li)))
        :else
        (throw (ex-info "Current plan status unknown" {}))))
