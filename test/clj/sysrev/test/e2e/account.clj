(ns sysrev.test.e2e.account
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.test.e2e.core :as e]
            [sysrev.util :as util]))

(defn log-out [{:keys [driver system]}]
  (doto driver
    e/check-browser-console-clean
    (et/click-visible :log-out-link)
    (ea/wait-visible :log-in-link)
    (ea/go (e/absolute-url system "/"))
    e/wait-until-loading-completes
    e/check-browser-console-clean)
  nil)

(defn log-in
  ([{:keys [driver] :as test-resources} {:keys [email password]}]
   (log/info "logging in" (str "(" email ")"))
   (e/go test-resources "/login")
   (doto driver
     (et/fill-visible :login-email-input email)
     (et/fill-visible :login-password-input password)
     (et/click-visible {:css "button[name='submit']"})
     e/wait-until-loading-completes
     e/check-browser-console-clean)
   (e/current-user-id driver)))
