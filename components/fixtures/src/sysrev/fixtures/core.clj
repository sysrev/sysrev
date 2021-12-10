(ns sysrev.fixtures.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.postgres.interface :as postgres]))

(def ^{:doc "Table names specified in the order that they should be loaded in.
             Fixtures are loaded from resources/sysrev/fixtures/{{name}}.edn"}
  table-names
  ["article-data"
   "groups"
   "project"
   "article"
   "label"
   "web-user"
   "session"
   "project-group"
   "project-member"
   "project-source"
   "article-source"
   "user-email"
   "user-group"])

(defn get-fixtures []
  (map #(->> (str "sysrev/fixtures/" % ".edn") io/resource slurp
             edn/read-string
             (vector (keyword %)))
       table-names))

(defn test-db? [db]
  (and (str/includes? (:dbname db) "_test")
       (not= 5470 (:port db)) ;; This is the port for the production DB
       (not= "sysrev.com" (get-in env [:selenium :host]))))

(defn ensure-test-db! [db]
  (when-not (test-db? db)
    (throw (RuntimeException.
            "Fixtures may only be loaded on dbs with _test in the name."))))

(defn load-fixtures! [& [db]]
  (let [db (or db @db/*active-db*)]
    (ensure-test-db! (:config db))
    (doseq [[table records] (get-fixtures)]
      (db/execute!
       (:datasource db)
       {:insert-into table
        :values records}))))

(defn wrap-fixtures [f]
  (let [config (-> (postgres/get-config)
                   (update :dbname #(str % (rand-int Integer/MAX_VALUE)))
                   (assoc :create-if-not-exists? true
                          :delete-on-stop? true))]
    (ensure-test-db! config)
    (let [pg (component/start (postgres/postgres config))]
      (binding [db/*active-db* (atom (db/make-db-config config))]
        (load-fixtures!)
        (f))
      (component/stop pg))))
