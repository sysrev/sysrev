(ns sysrev.postgres.core
  (:require [com.stuartsierra.component :as component]
            [hikari-cp.core :as hikari-cp]
            [next.jdbc :as jdbc]
            [sysrev.db.core :as db]
            [sysrev.config :refer [env]]
            [sysrev.flyway.interface :as flyway])
  (:import (org.postgresql.util PSQLException)))

(defn get-config [& [postgres-overrides]]
  (-> env :postgres
      (merge postgres-overrides)
      (update :dbtype #(or % "postgres"))))

(defn start-db! [& [postgres-overrides only-if-new]]
  (let [db-config (db/make-db-config (get-config postgres-overrides))]
    (flyway/migrate! (:datasource db-config))
    (db/set-active-db! db-config only-if-new)))

(defn make-datasource
  "Creates a Postgres db pool object to use with JDBC."
  [config]
  (-> {:minimum-idle 4
       :maximum-pool-size 10
       :adapter "postgresql"}
      (assoc :username (:user config)
             :password (:password config)
             :database-name (:dbname config)
             :server-name (:host config)
             :port-number (:port config))
      hikari-cp/make-datasource))

(defrecord Postgres [bound-port config datasource embedded-pg]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (let [embedded-pg (when (:embedded? config)
                          (->> config :port
                               ((requiring-resolve 'datapub.postgres-embedded/embedded-pg-builder))
                               ((requiring-resolve 'datapub.postgres-embedded/start!))))
            ;; If port 0 was specified, we need the actual port used.
            bound-port (if embedded-pg
                         ((requiring-resolve 'datapub.postgres-embedded/get-port) embedded-pg)
                         (:port config))
            config (assoc config :port bound-port)]
        (when (:create-if-not-exists? config)
          (let [ds (jdbc/get-datasource (dissoc config :dbname))]
            (try
              (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname config))])
              (catch PSQLException e
                (when-not (re-find #"database .* already exists" (.getMessage e))
                  (throw e))))))
        (let [datasource (make-datasource config)]
          (if embedded-pg
            (try
              (flyway/migrate! datasource)
              (catch Exception e
                ((requiring-resolve 'datapub.postgres-embedded/stop!) embedded-pg)
                (throw e)))
            (flyway/migrate! datasource))
          (assoc this
                 :bound-port bound-port
                 :datasource datasource
                 :embedded-pg embedded-pg
                 :query-cache db/*query-cache*
                 :query-cache-enabled db/*query-cache-enabled*)))))
  (stop [this]
    (if-not datasource
      this
      (do
        (hikari-cp/close-datasource datasource)
        (when (:delete-on-stop? config)
          (let [ds (jdbc/get-datasource (dissoc config :dbname))]
            (jdbc/execute! ds ["UPDATE pg_database SET datallowconn='false' WHERE datname=?" (:db-name config)])
            (jdbc/execute! ds ["SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=?" (:dbname config)])
            (jdbc/execute! ds [(str "DROP DATABASE IF EXISTS " (:dbname config))])))
        (when (:embedded? config)
          ((requiring-resolve 'datapub.postgres-embedded/stop!) embedded-pg))
        (assoc this
               :bound-port nil :datasource nil :embedded-pg nil
               :query-cache nil :query-cache-enabled nil)))))

(defn postgres [& [postgres-overrides]]
  (map->Postgres
   {:config (get-config postgres-overrides)}))
