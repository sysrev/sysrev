(ns sysrev.init
  (:require [sysrev.db.core :as db :refer [set-active-db! make-db-config]]
            [sysrev.web.core :refer [run-web]]
            [sysrev.config.core :refer [env]]
            [clojure.tools.logging :as log]))

(defn start-app [& [postgres-overrides server-port-override only-if-new]]
  (let [{profile :profile} env
        prod? (= profile :prod)
        {postgres-config :postgres} env
        postgres-config (merge postgres-config postgres-overrides)
        postgres-port (:port postgres-config)
        {{server-port :port} :server} env
        server-port (or server-port-override server-port)
        db-config (make-db-config postgres-config)]
    (set-active-db! db-config only-if-new)
    (run-web server-port prod? only-if-new)
    true))
