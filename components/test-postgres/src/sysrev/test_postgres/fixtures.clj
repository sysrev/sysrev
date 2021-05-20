(ns sysrev.test-postgres.fixtures
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [honeysql.types :as types]
            [sysrev.db.queries :as q])
  (:refer-clojure :exclude [read-string]))

(def ^{:doc "Table names specified in the order that they should be loaded in.
             Fixtures are loaded from resources/test-postgres/{{name}}.edn"}
  table-names
  ["web-user"])

(def readers
  {'sql/array types/read-sql-array})

(defn read-string [s]
  (edn/read-string {:readers readers} s))

(defn get-fixtures []
  (map #(->> (str "test-postgres/" % ".edn") io/resource slurp read-string
             (vector (keyword %)))
       table-names))

(defn load-fixtures []
  (doseq [[table records] (get-fixtures)]
    (q/create table records)))
