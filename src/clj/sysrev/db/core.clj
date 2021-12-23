(ns sysrev.db.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as j]
            [clj-time.coerce :as tc]
            clj-postgresql.core
            clj-postgresql.types
            [hikari-cp.core :refer [make-datasource close-datasource]]
            honey.sql
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [select from where]]
            [honeysql.format :as sqlf]
            honeysql-postgres.format
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as result-set]
            [postgre-types.json :refer [add-jsonb-type]]
            [sysrev.config :refer [env]]
            [sysrev.util :as util :refer [map-values in?]])
  (:import (java.sql Connection PreparedStatement)
           (org.joda.time DateTime)
           (org.postgresql.util PGobject PSQLException)))

;; for clj-kondo
(declare sql-identifier-to-clj)

(s/def ::cond #(contains? (where {} %) :where))

;;; Disable jdbc conversion from numeric to timestamp values
;;; (defined in clj-postgresql.types)
(extend-protocol j/ISQLParameter
  java.lang.Number
  (set-parameter [num ^java.sql.PreparedStatement s ^long i]
    (.setObject s i num)
    #_
    (let [_conn (.getConnection s)
          meta (.getParameterMetaData s)
          _type-name (.getParameterTypeName meta i)]
      (.setObject s i num))))

(doseq [op [(keyword "@@") (keyword "->") (keyword "->>")
            (keyword "#>") (keyword "#>>")]]
  (honey.sql/register-op! op))

(def jdbc-opts
  {:builder-fn result-set/as-kebab-maps})

(defn execute! [connectable sqlmap]
  (jdbc/execute! connectable (honey.sql/format sqlmap) jdbc-opts))

(defn execute-one! [connectable sqlmap]
  (jdbc/execute-one! connectable (honey.sql/format sqlmap) jdbc-opts))

;; https://github.com/seancorfield/next-jdbc/blob/develop/doc/tips-and-tricks.md
(defn ->pgobject
  "Transforms Clojure data to a PGobject that contains the data as
  JSON. PGObject type defaults to `jsonb` but can be changed via
  metadata key `:pgtype`"
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (json/write-str x)))))

(defn <-pgobject
  "Transform PGobject containing `json` or `jsonb` value to Clojure
  data."
  [^org.postgresql.util.PGobject v]
  (let [type  (.getType v)
        value (.getValue v)]
    (if (#{"jsonb" "json"} type)
      (when value
        (some-> (json/read-str value :key-fn keyword)
                (with-meta {:pgtype type})))
      value)))

;; if a SQL parameter is a Clojure hash map or vector, it'll be transformed
;; to a PGobject for JSON/JSONB:
(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (.setObject s i (->pgobject m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v))))

;; if a row contains a PGobject then we'll convert them to Clojure data
;; while reading (if column is either "json" or "jsonb" type):
(extend-protocol result-set/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^PGobject v _2 _3]
    (<-pgobject v)))

(declare clear-query-cache)

;; Active database connection pool object
(defonce ^:dynamic *active-db* (atom nil))

;; This is used to bind a transaction connection in with-transaction.
(defonce ^:dynamic *conn* nil)

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
    {:datasource (make-datasource (assoc options :leak-detection-threshold 30000))
     :datasource-long-running (make-datasource options)
     :config postgres-config}))

(defn close-active-db []
  (when-let [ds (:datasource @*active-db*)]
    (close-datasource ds))
  (reset! *active-db* nil))

(defn set-active-db!
  [db & [only-if-new]]
  (clear-query-cache)
  (when-not (and only-if-new (= (:config db) (:config @*active-db*)))
    (close-active-db)
    (reset! *active-db* db)
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
  ;; don't convert to jsonb if `x` is a honeysql map
  (if (and (map? x)
           (or (= (set (keys x)) (set [:name :args]))
               (-> x type str (str/includes? "honeysql"))))
    x
    (sql-cast (clojure.data.json/write-str x) :jsonb)))

(defn to-sql-array
  "Convert a Clojure sequence to a PostgreSQL array object.
  `sql-type` is the SQL type of the array elements."
  [sql-type elts & [conn]]
  (if-not (sequential? elts)
    elts
    (if conn
      (.createArrayOf ^Connection (:connection conn) sql-type (into-array elts))
      (j/with-db-transaction [conn (or *conn* @*active-db*)]
        (.createArrayOf ^Connection (:connection conn) sql-type (into-array elts))))))

(defn sql-array-contains [field val]
  [:= val (sql/call :any field)])

(defn-spec clj-identifier-to-sql string?
  "Convert a Clojure keyword or string identifier to SQL format by
  replacing all '-' with '_', returning a string."
  [identifier (s/or :k keyword? :s string?)]
  (-> identifier name str/lower-case (str/replace "-" "_")))

(defn-spec sql-identifier-to-clj string?
  "Convert an SQL keyword or string identifier to Clojure format by
  replacing all '_' with '-', returning a string."
  [identifier (s/or :k keyword? :s string?)]
  (-> identifier name str/lower-case (str/replace "_" "-")))

(defn prepare-honeysql-map
  "Converts map values to jsonb strings as needed."
  [m]
  (let [mapvals-to-json (partial map-values #(if (map? %) (to-jsonb %) %))]
    (cond-> m
      (contains? m :set)     (update :set mapvals-to-json)
      (contains? m :values)  (update :values (partial mapv mapvals-to-json)))))

(defn do-query
  "Run SQL query defined by honeysql SQL map."
  [sql-map & [conn]]
  (try
    (j/query (or conn *conn* @*active-db*)
             (-> sql-map prepare-honeysql-map (sql/format :quoting :ansi))
             {:identifiers sql-identifier-to-clj
              :result-set-fn vec})
    (catch PSQLException e
      (throw
       (ex-info
        (str "PSQLException: " (.getMessage e))
        {:sql-map sql-map}
        e)))))

(defn raw-query
  "Run a raw sql query for when there is no HoneySQL implementation of a SQL feature"
  [raw-sql & [conn]]
  (j/query (or conn *conn* @*active-db*)
           raw-sql
           {:identifiers sql-identifier-to-clj
            :result-set-fn vec}))

(defn do-execute
  "Execute SQL command defined by honeysql SQL map."
  [sql-map & [conn]]
  (j/execute! (or conn *conn* @*active-db*)
              (-> sql-map prepare-honeysql-map (sql/format :quoting :ansi))
              {:transaction? (nil? (or conn *conn*))}))

(defn minc-time-ms
  "Returns a monotonically increasing time value in milliseconds. No relation
  to wall-clock time. May be negative."
  []
  (Math/round (* (System/nanoTime) 0.000001)))

(defmacro log-time [{:keys [log-f max-time-ms]} & body]
  `(let [max-time-ms# ~max-time-ms
         start-time-ms# (minc-time-ms)
         result# (do ~@body)
         elapsed-time-ms# (- (minc-time-ms) start-time-ms#)]
     (when (> elapsed-time-ms# max-time-ms#)
       ;; Use an Exception to get the stack trace
       (~log-f (Exception. "dummy") elapsed-time-ms# max-time-ms#))
     result#))

(defn log-too-long-transaction [e elapsed-time-ms max-time-ms]
  (log/warn e "Transaction took too long:" elapsed-time-ms "ms"
            "(allowed" max-time-ms "ms)"))

(defmacro with-transaction
  "Run body wrapped in an SQL transaction. If *conn* is already bound to a
  transaction, will run body unmodified to use the existing transaction.

  `body` should not spawn threads that make SQL calls."
  [& body]
  (assert body "with-transaction: body must not be empty")
  `(log-time
    {:log-f log-too-long-transaction
     :max-time-ms 5000}
    (if *conn*
      (do ~@body)
      (j/with-db-transaction [conn# @*active-db*]
        (binding [*conn* conn#
                  *transaction-query-cache* (atom {})]
          (do ~@body))))))

(defmacro with-long-transaction
  "Run body wrapped in an SQL transaction. If *conn* is already bound to a
  transaction, will run body unmodified to use the existing transaction.
  Uses the long-running connection pool.

  `body` should not spawn threads that make SQL calls."
  [[name-sym postgres] & body]
  (assert body "with-long-transaction: body must not be empty")
  `(log-time
    {:log-f log-too-long-transaction
     :max-time-ms (* 30 60 1000)}
    (if *conn*
      (do ~@body)
      (j/with-db-transaction [conn# {:datasource (:datasource-long-running ~postgres)}]
        (binding [*conn* conn#
                  *transaction-query-cache* (atom {})]
          (let [~name-sym conn#]
            ~@body))))))

(defmacro with-rollback-transaction
  "Like with-transaction, but sets rollback-only option on the transaction,
  and will throw an exception if used within an existing transaction."
  [& body]
  (assert body "with-rollback-transaction: body must not be empty")
  `(do (assert (nil? *conn*)
               "with-rollback-transaction: can't be used within existing transaction")
       (j/with-db-transaction [conn# @*active-db*]
         (j/db-set-rollback-only! conn#)
         (binding [*conn* conn#]
           (do ~@body)))))

(defmacro ^:unused with-transaction-on-db
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
  (-> (j/query (or conn *conn* @*active-db*) "SELECT LOCALTIMESTAMP AS TIMESTAMP")
      first :timestamp))

;;
;; Add missing JSON write methods for some types.
;;

(defn- write-timestamp [object out options]
  (util/apply-keyargs json/write (util/write-time-string object) out
                      options))

(defn- write-object-str [object out options]
  (util/apply-keyargs json/write (str object) out
                      options))

(extend java.sql.Timestamp
  json/JSONWriter
  {:-write write-timestamp})

(extend java.util.UUID
  json/JSONWriter
  {:-write write-object-str})

(extend-protocol j/ISQLValue
  DateTime
  (sql-value [v] (tc/to-sql-date v)))

;;
;; Facility for caching results of expensive data queries
;;
(defonce ^:dynamic *query-cache* (atom {}))
(defonce ^:dynamic *transaction-query-cache* nil)

(defonce ^:dynamic *query-cache-enabled* (atom true))

(defn-spec ^:repl enable-query-cache boolean?
  [enabled boolean?]
  (reset! *query-cache-enabled* enabled))

(defmacro with-query-cache [field-path & body]
  (let [field-path (if (keyword? field-path)
                     [field-path] field-path)]
    `(let [cache# (if (and *conn* *transaction-query-cache*)
                    *transaction-query-cache*
                    *query-cache*)]
       (if (not @*query-cache-enabled*)
         (do ~@body)
         (let [field-path# ~field-path
               cache-val# (get-in @cache# field-path# :not-found)]
           (if (= cache-val# :not-found)
             (let [new-val# (do ~@body)]
               (swap! cache# assoc-in field-path# new-val#)
               new-val#)
             cache-val#))))))

(defmacro with-project-cache [project-id field-path & body]
  (let [field-path (if (keyword? field-path)
                     [field-path] field-path)]
    `(let [project-id# ~project-id
           field-path# ~field-path
           full-path# (concat [:project project-id#] field-path#)]
       (with-query-cache full-path# ~@body))))

(defn clear-query-cache [& [field-path]]
  (let [in-transaction (and *conn* *transaction-query-cache*)]
    (if (nil? field-path)
      (do (reset! *query-cache* {})
          (when in-transaction
            (reset! *transaction-query-cache* {})))
      (let [update-cache
            (fn [cache-state]
              (if (= 1 (count field-path))
                (dissoc cache-state (first field-path))
                (update-in cache-state
                           (butlast field-path)
                           #(dissoc % (last field-path)))))]
        (swap! *query-cache* update-cache)
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
        (swap! *query-cache* update-cache)
        (when in-transaction
          (swap! *transaction-query-cache* update-cache)))

      :else
      (let [update-cache #(assoc % :project {})]
        (swap! *query-cache* update-cache)
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
  (let [[sql & params] (sql/format sql :quoting :ansi :parameterizer :postgresql)]
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
    (try (set-active-db! (make-db-config (assoc postgres-overrides
                                                :dbname "postgres")))
         (-> (select (sql/call "pg_terminate_backend" :pid))
             (from :pg-stat-activity)
             (where [:= :datname dbname])
             do-query
             count)
         (finally (close-active-db)))))

(defmethod sqlf/fn-handler "textmatch" [_ a b & _more]
  (assert (nil? _more))
  (str (sqlf/to-sql-value a) " @@ " (sqlf/to-sql-value b)))

(defn notify! [topic & [^String x]]
  (let [topic (if (string? topic) topic (sqlf/to-sql topic))]
    (do-execute
     {:select [(honeysql.core/call :pg_notify topic x)]})))
