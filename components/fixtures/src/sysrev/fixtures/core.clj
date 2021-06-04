(ns sysrev.fixtures.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [honeysql.types :as types]
            [next.jdbc :as jdbc]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.postgres.interface :as postgres]))

(def ^{:doc "Table names specified in the order that they should be loaded in.
             Fixtures are loaded from resources/sysrev/fixtures/{{name}}.edn"}
  table-names
  ["groups"
   "project"
   "web-user"
   "project-member"
   "user-group"])

(def readers
  {'sql/array types/read-sql-array})

(defn read-string [s]
  (edn/read-string {:readers readers} s))

(defn get-fixtures []
  (map #(->> (str "sysrev/fixtures/" % ".edn") io/resource slurp read-string
             (vector (keyword %)))
       table-names))

(defn test-db? [db]
  (and (str/includes? (:dbname db) "_test")
       (not= 5470 (:port db)) ;; This is the port for the production DB
       (not= "sysrev.com" (get-in env [:selenium :host]))))

(defn ensure-test-db! [db]
  (when-not (test-db? db)
    (throw (RuntimeException.
            "Not allowed to load fixtures on production server."))))

(defn load-fixtures! []
  (doseq [[table records] (get-fixtures)]
    (ensure-test-db! (:config @db/active-db))
    (q/create table records)))

(defn wrap-fixtures [f]
  (let [old-config (:config @db/active-db)]
    ;; This is hacky. It would be better to have avoided global state.
    (when (seq old-config)
      (db/close-active-db)
      (db/terminate-db-connections old-config))
    (let [config (postgres/get-config)]
      (ensure-test-db! config)
      (let [ds (jdbc/get-datasource (dissoc config :dbname))]
        (jdbc/execute! ds [(str "DROP DATABASE IF EXISTS " (:dbname config))])
        (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname config))])))
    (postgres/start-db!)
    (load-fixtures!)
    (f)
    (db/set-active-db! (db/make-db-config old-config))))
