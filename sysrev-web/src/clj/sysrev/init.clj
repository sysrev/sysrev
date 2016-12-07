(ns sysrev.init
  (:require [sysrev.db.core :refer [set-active-db! make-db-config]]
            [sysrev.web.core :refer [run-web]]
            [sysrev.predict.report :refer [clear-predict-summary-cache]]
            [config.core :refer [env]]))

(defn start-app [& [postgres-overrides]]
  (let [{profile :profile} env
        prod? (= profile :prod)
        {postgres-config :postgres} env
        postgres-config (merge postgres-config postgres-overrides)
        postgres-port (:port postgres-config)
        {{server-port :port} :server} env]
    (do (set-active-db! (make-db-config postgres-config))
        (->> postgres-port (format "connected to postgres (port %s)") println)
        (run-web server-port prod?)
        (->> server-port (format "web server started (port %s)") println)
        (clear-predict-summary-cache)
        true)))
