(ns sysrev.postgres.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [hikari-cp.core :as hikari-cp]
            [prestancedesign.get-port :as get-port]
            [sysrev.db.core :as db]
            [sysrev.flyway.interface :as flyway])
  (:import (com.opentable.db.postgres.embedded EmbeddedPostgres EmbeddedPostgres$Builder PgBinaryResolver PgDirectoryResolver)))

(def binary-resolver
  (reify PgBinaryResolver
    (^java.io.InputStream getPgBinary [this ^String system ^String machine-hardware]
     (-> (format "postgres-%s-%s.txz" system machine-hardware)
         str/lower-case
         io/resource
         .openStream))))

(def directory-resolver
  (reify PgDirectoryResolver
    (getDirectory [this _override-working-directory]
      (io/file (System/getenv "POSTGRES_DIRECTORY")))))

(defn get-config [& [postgres-overrides]]
  (let [port (get-port/get-port)
        dbname (str "sysrev_test" port)
        db {:dbname dbname
            :dbtype "postgres"
            :host "localhost"
            :port port
            :user "postgres"}]
    (merge db postgres-overrides)))

(defn ^EmbeddedPostgres$Builder embedded-pg-builder [port]
  (if (System/getenv "POSTGRES_DIRECTORY")
    (-> (EmbeddedPostgres/builder)
        (.setPgDirectoryResolver directory-resolver)
        (.setPort port))
    (-> (EmbeddedPostgres/builder)
        (.setPgBinaryResolver binary-resolver)
        (.setPort port))))

(defn start-db! [& [postgres-overrides only-if-new]]
  (let [{:keys [dbname port] :as config} (get-config postgres-overrides)
        conn (-> (embedded-pg-builder port)
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
      (let [pg (.start (embedded-pg-builder (:port config)))]
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
