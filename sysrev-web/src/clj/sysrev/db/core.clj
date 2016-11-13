(ns sysrev.db.core
  (:require [clojure.java.jdbc :as j]
            [clj-postgresql.core :as pg]
            [jdbc.pool.c3p0 :as pool]
            [clojure.data.json :as json]
            [postgre-types.json :refer [add-jsonb-type]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [clojure.string :as str])
  (:import java.sql.Timestamp
           java.sql.Date
           java.util.UUID))

;; Active database connection pool object
(defonce active-db (atom nil))

;; Connection object to active database connection.
;; Need one of this available directly for some Java API calls.
(defonce active-conn (atom nil))

;; This is used to bind a transaction connection in do-transaction.
(defonce ^:dynamic *conn* nil)

;; Option controlling result format of do-query.
(defonce ^:dynamic *sql-array-results* false)

;; Web requests will bind this to the user's active project-id.
(defonce ^:dynamic *active-project* nil)

(defn set-default-project
  "Set a default value for *active-project*, for use in REPL."
  [project-id]
  (alter-var-root #'*active-project* (fn [_] project-id)))

(defn reset-active-conn []
  (reset! active-conn (j/get-connection @active-db)))

(defn set-db-config!
  "Sets the connection parameters for Postgres."
  [{:keys [dbname user password host port]}]
  (reset! active-db (pg/pool :dbname dbname
                             :user user
                             :password password
                             :host host
                             :port port))
  (reset-active-conn))

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

(defmacro with-retry-conn
  "Wrap a `body` that attempts to use `active-conn` with an exception handler
  to reset `active-conn` and try running `body` again in case of failure."
  [& body]
  `(try
     (do ~@body)
     (catch Throwable e#
       (do (reset-active-conn)
           ~@body))))

(defn to-sql-array
  "Convert a Clojure sequence to a PostgreSQL array object.
  `sql-type` is the SQL type of the array elements."
  [sql-type elts]
  (if-not (sequential? elts)
    elts
    (with-retry-conn
      (.createArrayOf @active-conn sql-type
                      (into-array elts)))))

(defn format-column-name [col]
  (-> col str/lower-case (str/replace "_" "-")))

(defmacro do-query
  "Run SQL query defined by honeysql SQL map."
  [sql-map & params-or-opts]
  `(let [conn# (if *conn* *conn* @active-db)]
     (j/query conn# (sql/format ~sql-map ~@params-or-opts)
              :identifiers format-column-name
              :as-arrays? *sql-array-results*)))

(defmacro with-debug-sql
  "Runs body with exception handler to print SQL error details."
  [& body]
  `(try
     (do ~@body)
     (catch Throwable e#
       (.printStackTrace (.getNextException e#)))))

(defmacro do-execute
  "Execute SQL command defined by honeysql SQL map."
  [sql-map & params-or-opts]
  `(let [conn# (if *conn* *conn* @active-db)]
     (j/execute! conn# (sql/format ~sql-map ~@params-or-opts))))

(defmacro do-transaction
  "Run body wrapped in an SQL transaction, or if already executing inside a
  transaction then run body unmodified."
  [& body]
  (let [helper (fn [f]
                 (if-not (nil? *conn*)
                   ;; Already running inside a transaction, don't start a new one
                   (apply f [])
                   (j/with-db-transaction [conn @active-db]
                     (binding [*conn* conn]
                       (apply f [])))))]
    `(apply ~helper [(fn [] ~@body)])))

(defmacro with-sql-array-results
  "Run body with option set for do-query to return rows in array format."
  [& body]
  (let [helper (fn [f]
                 (binding [*sql-array-results* true]
                   (apply f [])))]
    `(apply ~helper [(fn [] ~@body)])))

(defn sql-now
  "Query current time from database."
  []
  (-> (j/query @active-db "SELECT LOCALTIMESTAMP") first :timestamp))

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
