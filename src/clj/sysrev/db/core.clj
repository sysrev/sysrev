(ns sysrev.db.core
  (:require clj-postgresql.core
            clj-postgresql.types
            [clj-time.coerce :as tc]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as j]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hikari-cp.core :refer [close-datasource]]
            honey.sql
            honeysql-postgres.format
            [honeysql.core :as sql]
            [honeysql.format :as sqlf]
            [honeysql.helpers :as sqlh :refer [where]]
            [medley.core :as medley]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as result-set]
            [orchestra.core :refer [defn-spec]]
            [postgre-types.json :refer [add-jsonb-type]]
            [sysrev.config :refer [env]]
            [sysrev.memcached.interface :as mem]
            [sysrev.postgres.interface :as pg]
            [sysrev.util :as util :refer [in?]]
            [sysrev.util-lite.interface :as ul])
  (:import (java.sql Connection PreparedStatement)
           (org.joda.time DateTime)
           (org.postgresql.jdbc PgArray)
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
    #_(let [_conn (.getConnection s)
            meta (.getParameterMetaData s)
            _type-name (.getParameterTypeName meta i)]
        (.setObject s i num))))

;; https://github.com/seancorfield/next-jdbc/blob/develop/doc/tips-and-tricks.md
(defn <-pgobject
  "Transform PGobject containing `json` or `jsonb` value to Clojure
  data."
  [^org.postgresql.util.PGobject obj]
  (let [type  (.getType obj)
        value (.getValue obj)]
    (if (#{"jsonb" "json"} type)
      (when value
        (let [v (json/read-str value :key-fn keyword)]
          (if (isa? (class v) clojure.lang.IObj)
            (with-meta v {:pgtype type})
            v)))
      value)))

;; if a SQL parameter is a Clojure hash map or vector, it'll be transformed
;; to a PGobject for JSON/JSONB:
(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (.setObject s i (pg/jsonb-pgobject m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (pg/jsonb-pgobject v))))

;; if a row contains a PGobject then we'll convert them to Clojure data
;; while reading (if column is either "json" or "jsonb" type):
(extend-protocol result-set/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^PGobject v _2 _3]
    (<-pgobject v)))

(defn seq-array [^PgArray array]
  (when array
    (seq (.getArray array))))

(declare clear-query-cache)

;; Active database connection pool object
(defonce ^:dynamic *active-db* (atom nil))

;; This is used to bind a transaction connection in with-transaction.
(defonce ^:dynamic *conn* nil)

(defn connectable
  "Get a connectable from the current transaction or connection pool.
   Returns a type that next.jdbc expects."
  []
  (or (:connection *conn*) (:datasource @*active-db*)))

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
 json/write-str
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
  (let [mapvals-to-json (partial medley/map-vals #(if (map? %) (to-jsonb %) %))]
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

(defmacro retry-serial
  "Retry the body on serialization errors from postgres."
  [retry-opts & body]
  `(ul/retry
    (merge
     {:interval-ms 100
      :n 4
      :throw-pred (complement pg/serialization-error?)}
     ~retry-opts)
    ~@body))

(defmacro with-tx
  "Either use an existing :tx in the sr-context, or create a new transaction
  and assign it to :tx in the sr-context."
  [[binding sr-context] & body]
  `(let [sr-context# ~sr-context]
     (if-let [tx# (:tx sr-context#)]
       (let [~binding sr-context#] ~@body)
       (retry-serial
        (merge {:n 0} (:tx-retry-opts sr-context#))
        (jdbc/with-transaction [tx# (get-in sr-context# [:postgres :datasource])
                                {:isolation :serializable}]
          (let [~binding (assoc sr-context# :tx tx#)]
            ~@body))))))

(defmacro with-long-tx
  "Either use an existing :tx in the sr-context, or create a new transaction
  and assign it to :tx in the sr-context."
  [[binding sr-context] & body]
  `(let [sr-context# ~sr-context]
     (if-let [tx# (:tx sr-context#)]
       (let [~binding sr-context#] ~@body)
       (retry-serial
        (merge {:n 0} (:tx-retry-opts sr-context#))
        (jdbc/with-transaction [tx# (get-in sr-context# [:postgres :datasource-long-running])
                                {:isolation :serializable}]
          (let [~binding (assoc sr-context# :tx tx#)]
            ~@body))))))

(defn execute!
  "Execute a HoneySQL 2 map."
  [sr-context sqlmap]
  (with-tx [{:keys [tx]} sr-context]
    (pg/execute! tx sqlmap)))

(defn execute-one!
  "Execute a HoneySQL 2 map."
  [sr-context sqlmap]
  (with-tx [{:keys [tx]} sr-context]
    (pg/execute-one! tx sqlmap)))

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

(def sql-now (sql/call :now))

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

(defmacro cache [sr-context key ^Long ttl-sec & body]
  `(mem/cache (:memcached ~sr-context) (pr-str ~key) ~ttl-sec
              ~@body))

;;
;; Facility for caching results of expensive data queries
;;
(defonce ^:dynamic *query-cache* (atom {}))
(defonce ^:dynamic *transaction-query-cache* nil)

(defonce ^:dynamic *query-cache-enabled* (atom true))

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

(defmethod sqlf/fn-handler "textmatch" [_ a b & more]
  (assert (nil? more))
  (str (sqlf/to-sql-value a) " @@ " (sqlf/to-sql-value b)))

(defn notify! [topic & [^String x]]
  (let [topic (if (string? topic) topic (sqlf/to-sql topic))]
    (do-execute
     {:select [(honeysql.core/call :pg_notify topic x)]})))
