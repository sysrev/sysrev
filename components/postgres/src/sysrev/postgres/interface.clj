(ns sysrev.postgres.interface
  (:require [sysrev.postgres.core :as postgres]))

(defn start-db! [& [postgres-overrides only-if-new]]
  (postgres/start-db! postgres-overrides only-if-new))

(defn postgres
  "Return a record implementing com.stuartsierra.component/Lifecycle
  that starts and stops a connection pool to a postgres DB."
  [& [postgres-overrides]]
  (postgres/postgres postgres-overrides))
