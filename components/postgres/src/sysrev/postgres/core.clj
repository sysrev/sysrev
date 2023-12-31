(ns sysrev.postgres.core
  (:require [com.stuartsierra.component :as component]
            [hikari-cp.core :as hikari-cp]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [sysrev.flyway.interface :as flyway]
            [sysrev.json.interface :as json]
            [sysrev.postgres.embedded :as embedded]
            [sysrev.util-lite.interface :as ul])
  (:import (org.postgresql.util PGobject PSQLException)))

(defmacro with-tx
  [[name-sym context] & body]
  `(let [context# ~context]
     (if-let [tx# (:tx context#)]
       (let [~name-sym context#] ~@body)
       (jdbc/with-transaction [tx# (get-in context# [:datasource])
                               (merge {:isolation :serializable}
                                      (:jdbc-opts context#))]
         (let [~name-sym (assoc context# :tx tx#)]
           ~@body)))))

(defmacro with-read-tx
  [[name-sym context] & body]
  `(with-tx [~name-sym (assoc-in ~context [:jdbc-opts :read-only] true)]
     ~@body))

(defn jsonb-pgobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str x))))

(defn serialization-error? [^Throwable e]
  (and (or (instance? PSQLException e)
           (some->> e ex-cause (instance? PSQLException)))
       (->> e ex-message (re-find #"could not serialize access") boolean)))

(defmacro retry-serial
  [retry-opts & body]
  `(ul/retry
    (merge
     {:interval-ms 100
      :n 4
      :throw-pred (complement serialization-error?)}
     ~retry-opts)
    ~@body))

(defn make-datasource
  "Creates a Postgres db pool object to use with JDBC."
  [config]
  (-> {:adapter "postgresql"
       :allow-pool-suspension true
       :maximum-pool-size 10
       :minimum-idle 4}
      (assoc :username (:user config)
             :password (:password config)
             :database-name (:dbname config)
             :server-name (:host config)
             :port-number (:port config))
      hikari-cp/make-datasource))

(defn create-db-if-not-exists! [{:keys [template-dbname] :as opts}]
  (let [ds (jdbc/get-datasource (dissoc opts :dbname))]
    (try
      (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname opts)
                              (some->> template-dbname (str " TEMPLATE ")))])
      (catch PSQLException e
        (when-not (re-find #"database .* already exists" (.getMessage e))
          (throw e))))))

(defn create-template-db-if-not-exists! [{:keys [flyway-locations template-dbname] :as opts}]
  (try
    (jdbc/execute! (jdbc/get-datasource (dissoc opts :dbname))
                   [(str "CREATE DATABASE " template-dbname)])
    (catch PSQLException e
      (when-not (re-find #"database .* already exists" (.getMessage e))
        (throw e))))
  (-> (assoc opts :dbname template-dbname)
      jdbc/get-datasource
      (flyway/migrate! flyway-locations)))

(defn drop-db! [{:keys [dbname] :as opts}]
  (let [ds (jdbc/get-datasource (dissoc opts :dbname))]
    (jdbc/execute! ds ["UPDATE pg_database SET datallowconn='false' WHERE datname=?" dbname])
    (jdbc/execute! ds ["SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=?" dbname])
    (jdbc/execute! ds [(str "DROP DATABASE IF EXISTS " dbname)])))

(defrecord Postgres [bound-port config datasource datasource-long-running embedded-pg]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (let [{:keys [embedded flyway-locations template-dbname] :as opts}
            #__ (:postgres config)
            embedded-pg (when embedded (embedded/start! embedded))
            ;; If port 0 was specified, we need the actual port used.
            bound-port (:port (or embedded-pg opts))
            opts (assoc opts
                        :password (get-in config [:secrets :postgres :password] (:password opts))
                        :port bound-port)]
        (try
          (when (:create-if-not-exists? opts)
            (when template-dbname
              (create-template-db-if-not-exists! opts))
            (create-db-if-not-exists! opts))
          (let [datasource (make-datasource opts)
                datasource-long-running (make-datasource opts)]
            (when-not template-dbname
              (flyway/migrate! datasource-long-running flyway-locations))
            (assoc this
                   :bound-port bound-port
                   :datasource datasource
                   :datasource-long-running datasource-long-running
                   :embedded-pg embedded-pg
                   :query-cache (try @(requiring-resolve 'sysrev.db.core/*query-cache*)
                                     (catch java.io.FileNotFoundException _))
                   :query-cache-enabled (try @(requiring-resolve 'sysrev.db.core/*query-cache-enabled*)
                                             (catch java.io.FileNotFoundException _))))
          (catch Exception e
            (when embedded-pg
              ((:stop! embedded-pg)))
            (throw e))))))
  (stop [this]
    (if-not datasource
      this
      (let [opts (:postgres config)]
        (hikari-cp/close-datasource datasource)
        (hikari-cp/close-datasource datasource-long-running)
        (when (:delete-on-stop? opts)
          (drop-db! opts))
        (when embedded-pg
          ((:stop! embedded-pg)))
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

(def sql-opts {:checking :strict})

(defmacro wrap-ex-info [sqlmap & body]
  `(try
     ~@body
     (catch PSQLException e#
       (let [sqlmap# ~sqlmap]
         (throw (ex-info (str "Error during SQL execution: " (.getMessage e#))
                         {:sqlmap sqlmap# :sqlstr (sql/format sqlmap# sql-opts)}
                         e#))))))

(defn execute! [connectable sqlmap]
  (wrap-ex-info
   sqlmap
   (jdbc/execute! connectable (sql/format sqlmap sql-opts) jdbc-opts)))

(defn execute-one! [connectable sqlmap]
  (wrap-ex-info
   sqlmap
   (jdbc/execute-one! connectable (sql/format sqlmap sql-opts) jdbc-opts)))

(defn plan [connectable sqlmap]
  (wrap-ex-info
   sqlmap
   (jdbc/plan connectable (sql/format sqlmap sql-opts) jdbc-opts)))

(defn recreate-db! [{:keys [bound-port config datasource
                            query-cache query-cache-enabled]
                     :as postgres}]
  (let [pool (.getHikariPoolMXBean datasource)
        opts (-> (:postgres config) (assoc :port bound-port))]
    (.suspendPool pool)
    (.softEvictConnections pool)
    (drop-db! opts)
    (when query-cache
      (reset! query-cache {}))
    (create-db-if-not-exists! opts)
    (when query-cache
      (reset! query-cache {}))
    (.resumePool pool)
    postgres))
