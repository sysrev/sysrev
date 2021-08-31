(ns datapub.postgres
  (:require [com.stuartsierra.component :as component]
            [datapub.flyway :as flyway]
            [hikari-cp.core :as hikari-cp]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

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
      (do
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
                   :embedded-pg embedded-pg))))))
  (stop [this]
    (if-not datasource
      this
      (do
        (hikari-cp/close-datasource datasource)
        (when (:embedded? config)
          ((requiring-resolve 'datapub.postgres-embedded/stop!) embedded-pg))
        (assoc this :bound-port nil :datasource nil :embedded-pg nil)))))

(defn postgres [{:keys [config]}]
  (map->Postgres {:config config}))

;; Register postgres text search operator
(sql/register-op! (keyword "@@"))

(def jdbc-opts
  {:builder-fn result-set/as-kebab-maps})

(defn execute! [connectable sqlmap]
  (jdbc/execute! connectable (sql/format sqlmap) jdbc-opts))

(defn execute-one! [connectable sqlmap]
  (jdbc/execute-one! connectable (sql/format sqlmap) jdbc-opts))

(defn plan [connectable sqlmap]
  (jdbc/plan connectable (sql/format sqlmap) jdbc-opts))
