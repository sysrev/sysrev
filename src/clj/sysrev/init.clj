(ns sysrev.init
  (:require [sysrev.db.core :as db :refer [set-active-db! make-db-config]]
            [sysrev.web.core :refer [run-web]]
            [config.core :refer [env]]
            [clojure.tools.logging :as log]))

(defn start-app [& [postgres-overrides server-port-override]]
  (let [{profile :profile} env
        prod? (= profile :prod)
        {postgres-config :postgres} env
        postgres-config (merge postgres-config postgres-overrides)
        postgres-port (:port postgres-config)
        {{server-port :port} :server} env
        server-port (or server-port-override server-port)]
    (do (set-active-db! (make-db-config postgres-config))
        (let [{:keys [host port dbname]} postgres-config]
          (log/info (format "connected to postgres (%s:%d/%s)"
                            host port dbname)))
        (run-web server-port prod?)
        (log/info (format "web server started (port %s)" server-port))
        true)))
