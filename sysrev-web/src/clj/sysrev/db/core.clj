(ns sysrev.db.core
  (:require [clojure.java.jdbc :as j]
            [jdbc.pool.c3p0 :as pool]
            [clojure.data.json :as json]
            [postgre-types.json :refer [add-jsonb-type]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defonce active-db (atom nil))
(defonce ^:dynamic *conn* nil)
(defonce ^:dynamic *sql-array-results* false)

(defn set-db-config! [& {:keys [dbname user password port]
                         :or {dbname "systematic_review_webnew"
                              user "postgres"
                              password nil
                              port 5432}}]
  (reset! active-db (pool/make-datasource-spec
                     {:classname "org.postgresql.Driver"
                      :subprotocol "postgresql"
                      :user user
                      :password password
                      :subname (format "//localhost:%d/%s" port dbname)})))

(add-jsonb-type
 (fn [writer]
   (json/write-str writer))
 (fn [reader]
   (json/read-str reader :key-fn keyword)))

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
