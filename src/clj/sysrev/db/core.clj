(ns sysrev.db.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.util :refer [map-to-arglist]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [clojure.java.jdbc :as j]
            [clojure.data.json :as json]
            [clj-postgresql.core]
            [hikari-cp.core :refer [make-datasource close-datasource]]
            [postgre-types.json :refer [add-jsonb-type]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [sysrev.config.core :refer [env]]
            [clojure.string :as str])
  (:import (java.sql Timestamp Date Connection)
           java.util.UUID))

(declare clear-query-cache)

;; Active database connection pool object
(defonce active-db (atom nil))

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
    {:datasource (make-datasource options)
     :config postgres-config}))

(defn close-active-db []
  (when-let [ds (:datasource @active-db)]
    (close-datasource ds))
  (reset! active-db nil))

(defn set-active-db!
  [db & [only-if-new]]
  (clear-query-cache)
  (if (and only-if-new
           (= (:config db) (:config @active-db)))
    nil
    (do (close-active-db)
        (reset! active-db db)
        (when-not (in? [:test :remote-test] (:profile env))
          (let [{:keys [host port dbname]} (:config db)]
            (log/info (format "connected to postgres (%s:%d/%s)"
                              host port dbname))))
        db)))

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

(defn format-column-name [col]
  (-> col str/lower-case (str/replace "_" "-")))

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
           {:identifiers format-column-name
            :result-set-fn vec}))

(defn do-query-map
  "(->> (do-query ...) (map map-fn))"
  [sql-map map-fn & [conn]]
  (->> (do-query sql-map conn)
       (map map-fn)))

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
           (binding [*conn* conn#]
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

(defn time-to-string
  "Print time object to formatted time string."
  [t & [formatter]]
  (let [t (cond (= (type t) java.sql.Timestamp)
                (tc/from-sql-time t)
                (= (type t) java.sql.Date)
                (tc/from-sql-date t)
                :else t)
        formatter (or formatter :mysql)]
    (tf/unparse (tf/formatters formatter) t)))

;;
;; Add missing JSON write methods for some types.
;;

(defn- write-timestamp [x out]
  (json/write (time-to-string x) out))

(defn- write-object-str [x out]
  (json/write (str x) out))

(extend java.sql.Timestamp
  json/JSONWriter
  {:-write write-timestamp})

(extend java.util.UUID
  json/JSONWriter
  {:-write write-object-str})

;;
;; Facility for caching query results that shouldn't change often
;;
(defonce query-cache (atom {}))

(defonce query-cache-enabled (atom true))

(defn enable-query-cache [enable?]
  (reset! query-cache-enabled enable?))
;;
(s/fdef enable-query-cache
        :args (s/cat :enable? boolean?)
        :ret boolean?)

(defmacro with-query-cache [field-path form]
  (let [field-path (if (keyword? field-path)
                     [field-path] field-path)]
    `(if (or (not @query-cache-enabled) *conn*)
       (do ~form)
       (let [field-path# ~field-path
             cache-val# (get-in @query-cache field-path# :not-found)]
         (if (= cache-val# :not-found)
           (let [new-val# (do ~form)]
             (swap! query-cache assoc-in field-path# new-val#)
             new-val#)
           cache-val#)))))

(defmacro with-project-cache [project-id field-path form]
  (let [field-path (if (keyword? field-path)
                     [field-path] field-path)]
    `(let [project-id# ~project-id
           field-path# ~field-path
           full-path# (concat [:project project-id#] field-path#)]
       (with-query-cache full-path# ~form))))

(defn cached-project-ids []
  (keys (get @query-cache :project)))

(defn clear-query-cache [& [field-path]]
  (if (nil? field-path)
    (reset! query-cache {})
    (swap! query-cache
           (fn [cache-state]
             (if (= 1 (count field-path))
               (dissoc cache-state (first field-path))
               (update-in cache-state
                          (butlast field-path)
                          #(dissoc % (last field-path)))))))
  nil)

(defn clear-global-cache []
  (clear-query-cache [:all-labels]))

(defn clear-project-cache [& [project-id field-path clear-protected?]]
  (clear-global-cache)
  (cond
    (and project-id field-path)
    (clear-query-cache (concat [:project project-id] field-path))

    project-id
    (swap! query-cache
           #(assoc-in % [:project project-id]
                      {:protected
                       (if clear-protected? {}
                           (get-in % [:project project-id :protected]))}))

    :else
    (swap! query-cache assoc :project {}))
  nil)

(defn sql-field [table-name field-name]
  (keyword (str (name table-name) "." (name field-name))))

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
        (-> (select (sql/call :pg_terminate_backend :pid))
            (from :pg_stat_activity)
            (where [:= :datname dbname])
            do-query
            count)
        (finally
          (close-active-db))))))
