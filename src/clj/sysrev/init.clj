(ns sysrev.init
  (:require [clojure.tools.logging :as log]
            sysrev.logging
            sysrev.stacktrace
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.listeners :as listeners]
            [sysrev.db.migration :as migration]
            [sysrev.postgres.interface :as postgres]
            [sysrev.scheduler.core :refer [start-scheduler]]
            [sysrev.web.core :refer [run-web]]
            [sysrev.web.routes.site :as site])
  (:import [java.net BindException]))

(defn stop-db []
  (db/close-active-db)
  (listeners/stop-listeners!))

(defn start-web [& [server-port-override only-if-new]]
  (let [prod? (= (:profile env) :prod)
        server-port (or server-port-override
                        (-> env :server :port))]
    (try (run-web server-port prod? only-if-new)
         (catch BindException _
           (log/errorf "start-web: port %d already in use" server-port)))))

(defn start-app [& [postgres-overrides server-port-override only-if-new]]
  (postgres/start-db! postgres-overrides only-if-new)
  (site/init-global-stats)
  (listeners/start-listeners!)
  (when (= (:profile env) :dev)
    (migration/ensure-updated-db))
  (start-web server-port-override only-if-new)
  (start-scheduler)
  (listeners/start-listener-handlers!)
  true)
