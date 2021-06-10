(ns sysrev.fixtures.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [honeysql.types :as types]
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
   "project-group"
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
    (ensure-test-db! (:config @db/*active-db*))
    (q/create table records)))

(defn wrap-fixtures [f]
  (let [config (-> (postgres/get-config)
                   (update :dbname #(str % (rand-int Integer/MAX_VALUE)))
                   (assoc :create? true :delete-on-stop? true))]
    (ensure-test-db! config)
    (let [pg (component/start (postgres/postgres config))]
      (binding [db/*active-db* (atom (db/make-db-config config))]
        (load-fixtures!)
        (f))
      (component/stop pg))))
