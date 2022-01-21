(ns sysrev.postgres.core
  (:require
   [com.stuartsierra.component :as component]
   [hikari-cp.core :as hikari-cp]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as result-set]
   [sysrev.flyway.interface :as flyway])
  (:import
   (org.postgresql.util PSQLException)))

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
      (let [opts (:postgres config)
            embedded-pg (when (:embedded? opts)
                          (->> opts :port
                               ((requiring-resolve 'sysrev.postgres.embedded/embedded-pg-builder))
                               ((requiring-resolve 'sysrev.postgres.embedded/start!))))
            ;; If port 0 was specified, we need the actual port used.
            bound-port (if embedded-pg
                         ((requiring-resolve 'sysrev.postgres.embedded/get-port) embedded-pg)
                         (:port opts))
            opts (assoc opts
                        :password (get-in config [:secrets :postgres :password] (:password opts))
                        :port bound-port)]
        (when (:create-if-not-exists? opts)
          (let [ds (jdbc/get-datasource (dissoc opts :dbname))]
            (try
              (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname opts))])
              (catch PSQLException e
                (when-not (re-find #"database .* already exists" (.getMessage e))
                  (throw e))))))
        (let [datasource (make-datasource opts)
              datasource-long-running (make-datasource opts)]
          (if embedded-pg
            (try
              (flyway/migrate! datasource-long-running (:flyway-locations opts))
              (catch Exception e
                ((requiring-resolve 'sysrev.postgres.embedded/stop!) embedded-pg)
                (throw e)))
            (flyway/migrate! datasource-long-running (:flyway-locations opts)))
          (assoc this
                 :bound-port bound-port
                 :datasource datasource
                 :datasource-long-running datasource-long-running
                 :embedded-pg embedded-pg
                 :query-cache (try @(requiring-resolve 'sysrev.db.core/*query-cache*)
                                   (catch java.io.FileNotFoundException _))
                 :query-cache-enabled (try @(requiring-resolve 'sysrev.db.core/*query-cache-enabled*)
                                           (catch java.io.FileNotFoundException _)))))))
  (stop [this]
    (if-not datasource
      this
      (let [opts (:postgres config)]
        (hikari-cp/close-datasource datasource)
        (hikari-cp/close-datasource datasource-long-running)
        (when (:delete-on-stop? opts)
          (let [ds (jdbc/get-datasource (dissoc opts :dbname))]
            (jdbc/execute! ds ["UPDATE pg_database SET datallowconn='false' WHERE datname=?" (:db-name opts)])
            (jdbc/execute! ds ["SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=?" (:dbname opts)])
            (jdbc/execute! ds [(str "DROP DATABASE IF EXISTS " (:dbname opts))])))
        (when (:embedded? opts)
          ((requiring-resolve 'sysrev.postgres.embedded/stop!) embedded-pg))
        (assoc this
               :bound-port nil :datasource nil :datasource-long-running nil
               :embedded-pg nil :query-cache nil :query-cache-enabled nil)))))

(defn postgres []
  (map->Postgres {}))

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
