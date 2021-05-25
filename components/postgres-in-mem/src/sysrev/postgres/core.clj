(ns sysrev.postgres.core
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
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

(defn start-db [& [postgres-overrides only-if-new]]
  (let [db {:dbname "postgres"
            :dbtype "postgres"
            :host "localhost"
            :port (get-port/get-port)
            :user "postgres"}
        _conn (-> (EmbeddedPostgres/builder)
                  (.setPort (:port db))
                  .start
                  .getPostgresDatabase
                  .getConnection)
        db-config (db/make-db-config (merge db postgres-overrides))]
    (db/set-active-db! db-config only-if-new)
    (with-flyway-config db
      (log/info (str "Applying Flyway migrations...\n"
                     (str/trimr (slurp "flyway.conf"))))
      (if (ms-windows?)
        (shell ".flyway-5.2.4/flyway.cmd" "migrate")
        (shell "./flyway" "migrate")))))
