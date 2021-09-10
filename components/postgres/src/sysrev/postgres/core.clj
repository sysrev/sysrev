(ns sysrev.postgres.core
  (:require [com.stuartsierra.component :as component]
            [hikari-cp.core :as hikari-cp]
            [next.jdbc :as jdbc]
            [sysrev.db.core :as db]
            [sysrev.config :refer [env]]
            [sysrev.flyway.interface :as flyway]))

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

(defrecord Postgres [config datasource]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (do
        (when (:create? config)
          (let [ds (jdbc/get-datasource (dissoc config :dbname))]
            (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname config))])))
        (let [datasource (make-datasource config)]
          (flyway/migrate! datasource)
          (assoc this
                 :datasource datasource
                 :query-cache db/*query-cache* :query-cache-enabled db/*query-cache-enabled*)))))
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
        (assoc this :datasource nil)))))

(defn postgres [& [postgres-overrides]]
  (map->Postgres
   {:config (get-config postgres-overrides)}))
