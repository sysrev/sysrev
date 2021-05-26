(ns sysrev.fixtures.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [honeysql.types :as types]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.postgres.interface :as postgres]))

(def ^{:doc "Table names specified in the order that they should be loaded in.
             Fixtures are loaded from resources/test-postgres/{{name}}.edn"}
  table-names
  ["web-user"])

(def readers
  {'sql/array types/read-sql-array})

(defn read-string [s]
  (edn/read-string {:readers readers} s))

(defn get-fixtures []
  (map #(->> (str "sysrev/fixtures/" % ".edn") io/resource slurp read-string
             (vector (keyword %)))
       table-names))

(defn load-fixtures! []
  (doseq [[table records] (get-fixtures)]
    (q/create table records)))

(defn wrap-fixtures [f]
  (let [old-config (:config @db/active-db)]
    ;; This is hacky. It would be better to have avoided global state.
    (postgres/start-db)
    (load-fixtures!)
    (f)
    (db/set-active-db! (db/make-db-config old-config))))
