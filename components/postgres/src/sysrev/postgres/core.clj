(ns sysrev.postgres.core
  (:require [sysrev.db.core :as db]
            [sysrev.config :refer [env]]))

(defn start-db [& [postgres-overrides only-if-new]]
  (let [db-config (db/make-db-config
                   (merge (:postgres env) postgres-overrides))]
    (db/set-active-db! db-config only-if-new)))
