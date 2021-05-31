(ns sysrev.postgres.core
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [hikari-cp.core :as hikari-cp]
            [prestancedesign.get-port :as get-port]
            [sysrev.db.core :as db]
            [sysrev.config :refer [env]])
  (:import [com.opentable.db.postgres.embedded EmbeddedPostgres]))

(defn ms-windows? []
  (-> (System/getProperty "os.name")
      (str/includes? "Windows")))

(defn shell
  "Runs a shell command, throwing exception on non-zero exit."
  [& args]
  (let [{:keys [exit] :as result}
        (apply sh args)]
    (if (zero? exit)
      result
      (throw (ex-info "shell command returned non-zero exit code"
                      {:type :shell
                       :command (vec args)
                       :result result})))))

(defn write-flyway-config [& [postgres-overrides]]
  (let [{:keys [dbname user host port]}
        (merge (:postgres env) postgres-overrides)]
    (shell "mv" "-f" "flyway.conf" ".flyway.conf.moved")
    (spit "flyway.conf"
          (->> [(format "flyway.url=jdbc:postgresql://%s:%d/%s"
                        host port dbname)
                (format "flyway.user=%s" user)
                "flyway.password="
                "flyway.locations=filesystem:./resources/sql"
                ""]
               (str/join "\n")))))

(defn restore-flyway-config []
  (shell "mv" "-f" ".flyway.conf.moved" "flyway.conf"))

(defmacro with-flyway-config [postgres-overrides & body]
  `(try
     (write-flyway-config ~postgres-overrides)
     ~@body
     (finally
       (restore-flyway-config))))

(defn run-flyway! [db]
  (with-flyway-config db
    (log/info (str "Applying Flyway migrations...\n"
                   (str/trimr (slurp "flyway.conf"))))
    (if (ms-windows?)
      (shell ".flyway-5.2.4/flyway.cmd" "migrate")
      (shell "./flyway" "migrate"))))

(defn start-db! [& [postgres-overrides only-if-new]]
  (let [port (get-port/get-port)
        dbname (str "postgres" port)
        db {:dbname dbname
            :dbtype "postgres"
            :host "localhost"
            :port port
            :user "postgres"}
        conn (-> (EmbeddedPostgres/builder)
                 (.setPort port)
                 .start
                 .getPostgresDatabase
                 .getConnection)
        _ (-> conn .createStatement
              (.executeUpdate (str "CREATE DATABASE " dbname)))
        db-config (db/make-db-config (merge db postgres-overrides))]
    (db/set-active-db! db-config only-if-new)
    (run-flyway! db)))

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

(defrecord Postgres [config datasource pg]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (let [pg (-> (EmbeddedPostgres/builder)
                   (.setPort (:port config))
                   .start)]
        (-> pg .getPostgresDatabase .getConnection .createStatement
            (.executeUpdate (str "CREATE DATABASE " (:dbname config))))
        (run-flyway! config)
        (assoc this :datasource (make-datasource config) :pg pg))))
  (stop [this]
    (if-not datasource
      this
      (do
        (hikari-cp/close-datasource datasource)
        (.close pg)
        (assoc this :datasource nil :pg nil)))))

(defn postgres [& [postgres-overrides]]
  (let [port (get-port/get-port)
        dbname (str "postgres" port)
        db {:dbname dbname
            :dbtype "postgres"
            :host "localhost"
            :port port
            :user "postgres"}]
    (map->Postgres {:config (merge db postgres-overrides)})))
