(ns sysrev.init
  (:require [sysrev.db.core :refer [set-db-config!]]
            [sysrev.web.core :refer [run-web]]
            [config.core :refer [env]]))

(defn start-app []
  (let [{profile :profile} env
        prod? (= profile :prod)
        {postgres-config :postgres} env
        {{postgres-port :port} :postgres
         {server-port :port} :server} env]
    (do (set-db-config! postgres-config)
        (->> postgres-port (format "connected to postgres (port %s)") println)
        (run-web server-port prod?)
        (->> server-port (format "web server started (port %s)") println)
        true)))
