(ns sysrev.db.core
  (:require [clojure.java.jdbc :as j]
            [clj-postgresql.core :as pg]
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
  (reset! active-db (pg/pool :dbname dbname
                             :user user
                             :password password
                             :port port)))

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

(defn sql-now []
  (-> (j/query @active-db "SELECT LOCALTIMESTAMP") first :timestamp))

(defn mapify-by-id
  "Convert the sequence `entries` to a map, using the value under `id-key` from
  each entry as its map key."
  [entries id-key]
  (->> entries
       (mapv #(let [k (get % id-key)
                    m (dissoc % id-key)]
                [k m]))
       (apply concat)
       (apply hash-map)))
