(ns sysrev.db.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as j]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            clj-postgresql.core
            [hikari-cp.core :refer [make-datasource close-datasource]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [postgre-types.json :refer [add-jsonb-type]]
            [sysrev.config.core :refer [env]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.util :as util :refer [map-to-arglist]]
            [sysrev.shared.util :as sutil :refer [map-values in?]])
  (:import (java.sql Timestamp Date Connection)
           java.util.UUID))

(declare clear-query-cache)

;; Active database connection pool object
(defonce active-db (atom nil))

;; This is used to bind a transaction connection in with-transaction.
(defonce ^:dynamic *conn* nil)

;; Used by sysrev.entity; must be defined here to allow resetting in set-active-db!
(defonce entity-columns-cache (atom {}))

(defn make-db-config
  "Creates a Postgres db pool object to use with JDBC.

  Defaults to the configuration contained in `(:postgres env)`,
  overriding with any field values passed in `postgres-overrides`."
  [{:keys [dbname user password host port] :as postgres-overrides}]
  (let [postgres-defaults (:postgres env)
        postgres-config (merge postgres-defaults postgres-overrides)
        options
        (-> {:minimum-idle 4
             :maximum-pool-size 10
             :adapter "postgresql"}
            (assoc :username (:user postgres-config)
                   :password (:password postgres-config)
                   :database-name (:dbname postgres-config)
                   :server-name (:host postgres-config)
                   :port-number (:port postgres-config)))]
    {:datasource (make-datasource options)
     :config postgres-config}))

(defn close-active-db []
  (when-let [ds (:datasource @active-db)]
    (close-datasource ds))
  (reset! active-db nil))

(defn set-active-db!
  [db & [only-if-new]]
  (clear-query-cache)
  (when-not (and only-if-new (= (:config db) (:config @active-db)))
    (close-active-db)
    (reset! active-db db)
    (reset! entity-columns-cache {})
    (when-not (in? [:test :remote-test] (:profile env))
      (let [{:keys [host port dbname]} (:config db)]
        (log/info (format "connected to postgres (%s:%d/%s)"
                          host port dbname))))
    db))

;; Add JDBC conversion methods for Postgres jsonb type
(add-jsonb-type
 (fn [writer]
   (json/write-str writer))
 (fn [reader]
   (json/read-str reader :key-fn keyword)))

(defn sql-cast [x sql-type]
  (sql/call :cast x sql-type))

(defn to-jsonb [x]
  ;; don't convert to jsonb if `x` is a honeysql function call
  (if (and (map? x)
           (= (set (keys x))
              (set [:name :args])))
    x
    (sql-cast (clojure.data.json/write-str x) :jsonb)))

(defn to-sql-array
  "Convert a Clojure sequence to a PostgreSQL array object.
  `sql-type` is the SQL type of the array elements."
  [sql-type elts & [conn]]
  (if-not (sequential? elts)
    elts
    (if conn
      (.createArrayOf (:connection conn) sql-type (into-array elts))
      (j/with-db-transaction [conn (or *conn* @active-db)]
        (try
          (.createArrayOf (:connection conn) sql-type (into-array elts))
          #_ (finally
               (when (nil? *conn*)
                 (.close (:connection conn)))))))))

(defn sql-array-contains [field val]
  [:= val (sql/call :any field)])

(defn clj-identifier-to-sql
  "Convert a Clojure keyword or string identifier to SQL format by
  replacing all '-' with '_', returning a string."
  [identifier]
  (-> identifier name str/lower-case (str/replace "-" "_")))
;;;
(s/fdef clj-identifier-to-sql
  :args (s/cat :identifier (s/or :keyword keyword? :string string?))
  :ret string?)

(defn sql-identifier-to-clj
  "Convert an SQL keyword or string identifier to Clojure format by
  replacing all '_' with '-', returning a string."
  [identifier]
  (-> identifier name str/lower-case (str/replace "_" "-")))
;;;
(s/fdef sql-identifier-to-clj
  :args (s/cat :identifier (s/or :keyword keyword? :string string?))
  :ret string?)

(defn prepare-honeysql-map
  "Converts map values to jsonb strings as needed."
  [m]
  (let [mapvals-to-json
        (partial map-values
                 #(if (map? %) (to-jsonb %) %))]
    (cond-> m
      (contains? m :set)
      (update :set mapvals-to-json)
      (contains? m :values)
      (update :values (partial mapv mapvals-to-json)))))

(defn do-query
  "Run SQL query defined by honeysql SQL map."
  [sql-map & [conn]]
  (j/query (or conn *conn* @active-db)
           (-> sql-map prepare-honeysql-map (sql/format :quoting :ansi))
           {:identifiers sql-identifier-to-clj
            :result-set-fn vec}))

(defn raw-query
  "Run a raw sql query for when there is no HoneySQL implementation of a SQL feature"
  [raw-sql & [conn]]
  (j/query (or conn *conn* @active-db)
           raw-sql
           {:identifiers sql-identifier-to-clj
            :result-set-fn vec}))

(defmacro with-debug-sql
  "Runs body with exception handler to print SQL error details."
  [& body]
  `(try
     (do ~@body)
     (catch Throwable e#
       (.printStackTrace (.getNextException e#)))))

(defn do-execute
  "Execute SQL command defined by honeysql SQL map."
  [sql-map & [conn]]
  (j/execute! (or conn *conn* @active-db)
              (-> sql-map prepare-honeysql-map (sql/format :quoting :ansi))
              {:transaction? (nil? (or conn *conn*))}))

(defmacro with-transaction
  "Run body wrapped in an SQL transaction. If *conn* is already bound to a
  transaction, will run body unmodified to use the existing transaction.

  `body` should not spawn threads that make SQL calls."
  [& body]
  (assert body "with-transaction: body must not be empty")
  `(do (if *conn*
         (do ~@body)
         (j/with-db-transaction [conn# @active-db]
           (binding [*conn* conn#
                     *transaction-query-cache* (atom {})]
             (do ~@body))))))

(defmacro with-rollback-transaction
  "Like with-transaction, but sets rollback-only option on the transaction,
  and will throw an exception if used within an existing transaction."
  [& body]
  (assert body "with-rollback-transaction: body must not be empty")
  `(do (assert (nil? *conn*)
               "with-rollback-transaction: can't be used within existing transaction")
       (j/with-db-transaction [conn# @active-db]
         (j/db-set-rollback-only! conn#)
         (binding [*conn* conn#]
           (do ~@body)))))

(defmacro with-transaction-on-db
  "Like with-transaction, but takes a db value as an argument instead
  of using the value from the active-db atom."
  [db & body]
  (assert db "with-transaction-on-db: db must not be nil")
  (assert body "with-transaction-on-db: body must not be empty")
  `(do (if *conn*
         (do ~@body)
         (j/with-db-transaction [conn# ~db]
           (binding [*conn* conn#]
             (do ~@body))))))

(defn sql-now
  "Query current time from database."
  [& [conn]]
  (-> (j/query (or conn *conn* @active-db) "SELECT LOCALTIMESTAMP AS TIMESTAMP")
      first :timestamp))

;;
;; Add missing JSON write methods for some types.
;;

(defn- write-timestamp [x out]
  (json/write (util/write-time-string x) out))

(defn- write-object-str [x out]
  (json/write (str x) out))

(extend java.sql.Timestamp
  json/JSONWriter
  {:-write write-timestamp})

(extend java.util.UUID
  json/JSONWriter
  {:-write write-object-str})

;;
;; Facility for caching results of expensive data queries
;;
(defonce query-cache (atom {}))
(defonce ^:dynamic *transaction-query-cache* nil)

(defonce query-cache-enabled (atom true))

(defn ^:repl enable-query-cache [enable?]
  (reset! query-cache-enabled enable?))
;;
(s/fdef enable-query-cache
        :args (s/cat :enable? boolean?)
        :ret boolean?)

(defmacro with-query-cache [field-path form]
  (let [field-path (if (keyword? field-path)
                     [field-path] field-path)]
    `(let [cache# (if (and *conn* *transaction-query-cache*)
                    *transaction-query-cache*
                    query-cache)]
       (if (not @query-cache-enabled)
         (do ~form)
         (let [field-path# ~field-path
               cache-val# (get-in @cache# field-path# :not-found)]
           (if (= cache-val# :not-found)
             (let [new-val# (do ~form)]
               (swap! cache# assoc-in field-path# new-val#)
               new-val#)
             cache-val#))))))

(defmacro with-project-cache [project-id field-path form]
  (let [field-path (if (keyword? field-path)
                     [field-path] field-path)]
    `(let [project-id# ~project-id
           field-path# ~field-path
           full-path# (concat [:project project-id#] field-path#)]
       (with-query-cache full-path# ~form))))

(defn clear-query-cache [& [field-path]]
  (let [in-transaction (and *conn* *transaction-query-cache*)]
    (if (nil? field-path)
      (do (reset! query-cache {})
          (when in-transaction
            (reset! *transaction-query-cache* {})))
      (let [update-cache
            (fn [cache-state]
              (if (= 1 (count field-path))
                (dissoc cache-state (first field-path))
                (update-in cache-state
                           (butlast field-path)
                           #(dissoc % (last field-path)))))]
        (swap! query-cache update-cache)
        (when in-transaction
          (swap! *transaction-query-cache* update-cache)))))
  nil)

(defn clear-project-cache [& [project-id field-path clear-protected?]]
  (let [in-transaction (and *conn* *transaction-query-cache*)]
    (cond
      (and project-id field-path)
      (clear-query-cache (concat [:project project-id] field-path))

      project-id
      (let [update-cache
            #(assoc-in % [:project project-id]
                       {:protected
                        (if clear-protected? {}
                            (get-in % [:project project-id :protected]))})]
        (swap! query-cache update-cache)
        (when in-transaction
          (swap! *transaction-query-cache* update-cache)))

      :else
      (let [update-cache #(assoc % :project {})]
        (swap! query-cache update-cache)
        (when in-transaction
          (swap! *transaction-query-cache* update-cache))))
    nil))

(defmacro with-clear-project-cache [project-id & body]
  `(with-transaction
     (let [project-id# ~project-id]
       (try ~@body (finally (some-> project-id# (clear-project-cache)))))))

(defn sql-field [table-name field-name]
  (keyword (str (name table-name) "." (name field-name))))

(defn table-fields [table-name field-names]
  (mapv #(sql-field table-name %) field-names))

(defn to-sql-string [sql]
  (let [[sql & params] (sql/format sql :quoting :ansi :parameterizer :postgresql)
        n-params (count params)]
    (reduce (fn [sql [i param]]
              (str/replace sql (str "$" (inc i))
                           (-> param sql/inline sql/format first (#(format "'%s'" %)))))
            sql
            (map-indexed (fn [i param] [i param])
                         params))))

(defn terminate-db-connections
  "Disconnect all clients from named Postgres database"
  [& [postgres-overrides]]
  (let [{:keys [dbname]} (merge (:postgres env) postgres-overrides)]
    (try
      (set-active-db! (-> postgres-overrides
                          (merge {:dbname "postgres"})
                          make-db-config))
      (try
        (-> (select (sql/call "pg_terminate_backend" :pid))
            (from :pg-stat-activity)
            (where [:= :datname dbname])
            do-query
            count)
        (finally
          (close-active-db))))))
