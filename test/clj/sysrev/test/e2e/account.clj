(ns sysrev.test.e2e.account
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
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
      (et/fill-visible :login-email-input email)
      (et/fill-visible :login-password-input password)
      (et/click-visible "//button[contains(text(),'Register')]")
      (ea/wait-visible :new-project))
    {:email email :password password}))

(defn log-out [{:keys [driver]}]
  (doto driver
    (et/click-visible :log-out-link)
    (ea/wait-visible :log-in-link)))

(defn log-in
  ([{:keys [driver] :as test-resources} {:keys [email password]}]
   (log/info "logging in" (str "(" email ")"))
   (e/go test-resources "/login")
   (doto driver
     (et/fill-visible :login-email-input email)
     (et/fill-visible :login-password-input password)
     (et/click-visible {:css "button[name='submit']"})
     e/wait-until-loading-completes)))
