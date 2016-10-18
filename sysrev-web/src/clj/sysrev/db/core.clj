(ns sysrev.db.core
  (:require [clojure.java.jdbc :as j]
            [clj-postgresql.core :as pg]
            [jdbc.pool.c3p0 :as pool]
            [clojure.data.json :as json]
            [postgre-types.json :refer [add-jsonb-type]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc])
  (:import java.sql.Timestamp
           java.sql.Date))

(defonce active-db (atom nil))
(defonce ^:dynamic *conn* nil)
(defonce ^:dynamic *sql-array-results* false)

(defn set-db-config! [{:keys [dbname user password host port]}]
  (reset! active-db (pg/pool :dbname dbname
                             :user user
                             :password password
                             :host host
                             :port port)))

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

(defmacro do-query [sql-map & params-or-opts]
  `(let [conn# (if *conn* *conn* @active-db)]
     (j/query conn# (sql/format ~sql-map ~@params-or-opts)
              :as-arrays? *sql-array-results*)))
(defmacro do-execute [sql-map & params-or-opts]
  `(let [conn# (if *conn* *conn* @active-db)]
     (j/execute! conn# (sql/format ~sql-map ~@params-or-opts))))

(defmacro do-transaction [& body]
  (let [helper (fn [f]
                 (j/with-db-transaction [conn @active-db]
                   (binding [*conn* conn]
                     (apply f []))))]
    `(apply ~helper [(fn [] ~@body)])))

(defmacro with-sql-array-results [& body]
  (let [helper (fn [f]
                 (binding [*sql-array-results* true]
                   (apply f [])))]
    `(apply ~helper [(fn [] ~@body)])))

(defn sql-now []
  (-> (j/query @active-db "SELECT LOCALTIMESTAMP") first :timestamp))

(defn time-to-string [t & [formatter]]
  (let [t (cond (= (type t) java.sql.Timestamp)
                (tc/from-sql-time t)
                (= (type t) java.sql.Date)
                (tc/from-sql-date t)
                :else t)
        formatter (or formatter :mysql)]
    (tf/unparse (tf/formatters formatter) t)))
