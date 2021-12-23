(ns sysrev.postgres.core
  (:require
   [com.stuartsierra.component :as component]
   [hikari-cp.core :as hikari-cp]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as result-set]
   [sysrev.config :refer [env]]
   [sysrev.db.core :as db]
   [sysrev.flyway.interface :as flyway])
  (:import
   (org.postgresql.util PSQLException)))

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

(defrecord Postgres [bound-port config datasource datasource-long-running embedded-pg]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (let [embedded-pg (when (:embedded? config)
                          (->> config :port
                               ((requiring-resolve 'sysrev.postgres.embedded/embedded-pg-builder))
                               ((requiring-resolve 'sysrev.postgres.embedded/start!))))
            ;; If port 0 was specified, we need the actual port used.
            bound-port (if embedded-pg
                         ((requiring-resolve 'sysrev.postgres.embedded/get-port) embedded-pg)
                         (:port config))
            config (assoc config :port bound-port)]
        (when (:create-if-not-exists? config)
          (let [ds (jdbc/get-datasource (dissoc config :dbname))]
            (try
              (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname config))])
              (catch PSQLException e
                (when-not (re-find #"database .* already exists" (.getMessage e))
                  (throw e))))))
        (let [datasource (make-datasource config)
              datasource-long-running (make-datasource config)]
          (if embedded-pg
            (try
              (flyway/migrate! datasource)
              (catch Exception e
                ((requiring-resolve 'sysrev.postgres.embedded/stop!) embedded-pg)
                (throw e)))
            (flyway/migrate! datasource))
          (assoc this
                 :bound-port bound-port
                 :datasource datasource
                 :datasource-long-running datasource-long-running
                 :embedded-pg embedded-pg
                 :query-cache db/*query-cache*
                 :query-cache-enabled db/*query-cache-enabled*)))))
  (stop [this]
    (if-not datasource
      this
      (do
        (hikari-cp/close-datasource datasource)
        (hikari-cp/close-datasource datasource-long-running)
        (when (:delete-on-stop? config)
          (let [ds (jdbc/get-datasource (dissoc config :dbname))]
            (jdbc/execute! ds ["UPDATE pg_database SET datallowconn='false' WHERE datname=?" (:db-name config)])
            (jdbc/execute! ds ["SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=?" (:dbname config)])
            (jdbc/execute! ds [(str "DROP DATABASE IF EXISTS " (:dbname config))])))
        (when (:embedded? config)
          ((requiring-resolve 'sysrev.postgres.embedded/stop!) embedded-pg))
        (assoc this
               :bound-port nil :datasource nil :datasource-long-running nil
               :embedded-pg nil :query-cache nil :query-cache-enabled nil)))))

(defn postgres [& [postgres-overrides]]
  (map->Postgres
   {:config (get-config postgres-overrides)}))

(doseq [op [(keyword "@@") ;; Register postgres text search operator
            ;; Register JSON operators
            (keyword "->") (keyword "->>") (keyword "#>") (keyword "#>>")]]
  (sql/register-op! op))

(def jdbc-opts
  {:builder-fn result-set/as-kebab-maps})

(defn execute! [connectable sqlmap]
  (jdbc/execute! connectable (sql/format sqlmap) jdbc-opts))

(defn execute-one! [connectable sqlmap]
  (jdbc/execute-one! connectable (sql/format sqlmap) jdbc-opts))

(defn plan [connectable sqlmap]
  (jdbc/plan connectable (sql/format sqlmap) jdbc-opts))
