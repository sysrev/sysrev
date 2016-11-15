(ns sysrev.db.core
  (:require [sysrev.util :refer [map-to-arglist]]
            [clojure.java.jdbc :as j]
            [clj-postgresql.core :as pg]
            [jdbc.pool.c3p0 :as pool]
            [clojure.data.json :as json]
            [postgre-types.json :refer [add-jsonb-type]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [config.core :refer [env]]
            [clojure.string :as str])
  (:import java.sql.Timestamp
           java.sql.Date
           java.util.UUID))

;; Active database connection pool object
(defonce active-db (atom nil))

;; This is used to bind a transaction connection in do-transaction.
(defonce ^:dynamic *conn* nil)

(defn make-db-config
  "Creates a Postgres db pool object to use with JDBC.

  Defaults to the configuration contained in `(:postgres env)`,
  overriding with any field values passed in `postgres-overrides`."
  [{:keys [dbname user password host port] :as postgres-overrides}]
  (let [postgres-defaults (:postgres env)
        postgres-config (merge postgres-defaults postgres-overrides)]
    (apply pg/pool (map-to-arglist postgres-config))))

(defn set-active-db!
  [db]
  (reset! active-db db))

;; Add JDBC conversion methods for Postgres jsonb type
(add-jsonb-type
 (fn [writer]
   (json/write-str writer))
 (fn [reader]
   (json/read-str reader :key-fn keyword)))

(defn to-jsonb
  "Converts a Clojure map to a honeysql jsonb string object
   which can be used in SQL queries."
  [map]
  (sql/call :jsonb (clojure.data.json/write-str map)))

(defn sql-cast [field sql-type]
  (sql/call :cast field sql-type))

(defn to-sql-array
  "Convert a Clojure sequence to a PostgreSQL array object.
  `sql-type` is the SQL type of the array elements."
  [sql-type elts & [conn]]
  (if-not (sequential? elts)
    elts
    (if conn
      (.createArrayOf (:connection conn) sql-type (into-array elts))
      (j/with-db-connection [conn (or *conn* @active-db)]
        (.createArrayOf (:connection conn) sql-type (into-array elts))))))

(defn format-column-name [col]
  (-> col str/lower-case (str/replace "_" "-")))

(defn do-query
  "Run SQL query defined by honeysql SQL map."
  [sql-map & [conn]]
  (j/query (or conn *conn* @active-db)
           (sql/format sql-map)
           :identifiers format-column-name
           :result-set-fn vec))

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
              (sql/format sql-map)
              :transaction? false))

(defmacro do-transaction
  "Run body wrapped in an SQL transaction.

  Uses thread-local dynamic binding to hold transaction connection value, so
  `body` must not make any SQL calls in spawned threads."
  [db & body]
  `(do (assert (nil? *conn*))
       (j/with-db-transaction [conn# (or ~db @active-db)]
         (binding [*conn* conn#]
           (do ~@body)))))

(defn sql-now
  "Query current time from database."
  [& [conn]]
  (-> (j/query (or conn *conn* @active-db) "SELECT LOCALTIMESTAMP")
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
