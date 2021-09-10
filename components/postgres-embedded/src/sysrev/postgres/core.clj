(ns sysrev.postgres.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [hikari-cp.core :as hikari-cp]
            [prestancedesign.get-port :as get-port]
            [sysrev.db.core :as db]
            [sysrev.flyway.interface :as flyway])
  (:import [com.opentable.db.postgres.embedded EmbeddedPostgres PgBinaryResolver]))

(set! *warn-on-reflection* true)

(def resolver
  (reify PgBinaryResolver
    (^java.io.InputStream getPgBinary [this ^String system ^String machine-hardware]
      (-> (format "postgres-%s-%s.txz" system machine-hardware)
          str/lower-case
          io/resource
          .openStream))))

(defn get-config [& [postgres-overrides]]
  (let [port (get-port/get-port)
        dbname (str "sysrev_test" port)
        db {:dbname dbname
            :dbtype "postgres"
            :host "localhost"
            :port port
            :user "postgres"}]
    (merge db postgres-overrides)))

(defn start-db! [& [postgres-overrides only-if-new]]
  (let [{:keys [dbname port] :as config} (get-config postgres-overrides)
        conn (-> (EmbeddedPostgres/builder)
                 (.setPgBinaryResolver resolver)
                 (.setPort port)
                 .start
                 .getPostgresDatabase
                 .getConnection)
        _ (-> conn .createStatement
              (.executeUpdate (str "CREATE DATABASE " dbname)))
        db-config (db/make-db-config config)]
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

(defrecord Postgres [config datasource ^EmbeddedPostgres pg]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (let [pg (-> (EmbeddedPostgres/builder)
                   (.setPgBinaryResolver resolver)
                   (.setPort (:port config))
                   .start)]
        (-> pg .getPostgresDatabase .getConnection .createStatement
            (.executeUpdate (str "CREATE DATABASE " (:dbname config))))
        (let [datasource (make-datasource config)]
          (flyway/migrate! datasource)
          (assoc this
                 :datasource datasource :pg pg
                 :query-cache db/*query-cache* :query-cache-enabled db/*query-cache-enabled*)))))
  (stop [this]
    (if-not datasource
      this
      (do
        (hikari-cp/close-datasource datasource)
        (.close pg)
        (assoc this :datasource nil :pg nil)))))

(defn postgres [& [postgres-overrides]]
  (map->Postgres {:config (get-config postgres-overrides)}))
