(ns sysrev.fixtures.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.postgres.interface :as pg]))

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

(defn prod-db? [db]
  (or (= 5470 (:port db))
      (= "sysrev.com" (get-in env [:selenium :host]))))

(defn ensure-not-prod-db! [db]
  (when (prod-db? db)
    (throw (RuntimeException.
            "Fixtures may not be loaded on the production DB"))))

(defn load-fixtures! [& [db]]
  (let [db (or db @db/*active-db*)]
    (ensure-not-prod-db! (:config db))
    (doseq [[table records] (get-fixtures)]
      (pg/execute!
       (:datasource db)
       {:insert-into table
        :values records}))))

(defn wrap-fixtures [f]
  (let [config (-> (pg/get-config)
                   (update :dbname #(str % (rand-int Integer/MAX_VALUE)))
                   (assoc :create-if-not-exists? true
                          :delete-on-stop? true))]
    (ensure-not-prod-db! config)
    (let [pg (component/start (pg/postgres config))]
      (binding [db/*active-db* (atom (db/make-db-config config))]
        (load-fixtures!)
        (f))
      (component/stop pg))))
