(ns sysrev.postgres.interface
  (:require [sysrev.postgres.core :as postgres]))

(defn start-db [& [postgres-overrides only-if-new]]
  (postgres/start-db postgres-overrides only-if-new))
