(ns sysrev.init
  (:require [clojure.tools.logging :as log]
            sysrev.logging
            sysrev.stacktrace
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.migration :as migration]
            [sysrev.main :as main]
            [sysrev.postgres.interface :as postgres]
            [sysrev.scheduler.core :refer [start-scheduler]]
            [sysrev.web.routes.site :as site]))

(defn stop-db []
  (db/close-active-db))

(defn start-app [& [postgres-overrides only-if-new]]
  (postgres/start-db! postgres-overrides only-if-new)
  (site/init-global-stats)
  (when (= (:profile env) :dev)
    (migration/ensure-updated-db))
  (main/start-system!)
  (start-scheduler)
  true)
