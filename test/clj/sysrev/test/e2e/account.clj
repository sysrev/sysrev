(ns sysrev.test.e2e.account
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.payment.plans :as plans]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.core :as e]
   [sysrev.util :as util]))

(def default-email "test@example.com")
(def default-password "testexample")

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
      (et/click-visible "//button[contains(text(),'Register')]")
      (e/wait-exists :new-project))
    {:email email :password password}))

(defn log-out [{:keys [driver]}]
  (doto driver
    (et/click-visible :log-out-link)
    (ea/wait-visible :log-in-link)))

(defn log-in
  ([{:keys [driver system]} {:keys [email password]}]
   (log/info "logging in" (str "(" email ")"))
   (doto driver
     (ea/go (e/absolute-url system "/login"))
     (ea/wait-exists :login-email-input)
     (ea/fill-multi {:login-email-input email
                     :login-password-input password})
     (et/click-visible {:css "button[name='submit']"})
     (ea/wait-visible {:fn/has-text "Your Projects"}))))

(defn change-user-plan! [{:keys [system]} user-id plan-nickname]
  (let [plan (test/execute-one!
              system
              {:select :id
               :from :stripe-plan
               :where [:= :nickname plan-nickname]})]
    (when-not plan
      (throw (ex-info "Plan not found" {:nickname plan-nickname})))
    (plans/add-user-to-plan! user-id (:stripe-plan/id plan) "fake_subscription")))

