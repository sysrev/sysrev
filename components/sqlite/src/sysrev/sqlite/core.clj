(ns sysrev.sqlite.core
  (:require [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]))

(defrecord SQLite [datasource db-spec]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (assoc this :datasource (jdbc/get-datasource db-spec))))
  (stop [this]
    (if datasource
      (assoc this :datasource nil)
      this)))

(defn sqlite [filename]
  (map->SQLite {:db-spec {:dbname filename :dbtype "sqlite"}}))

(def jdbc-opts
  {:builder-fn result-set/as-kebab-maps})

(defn execute! [connectable sqlmap]
  (jdbc/execute! connectable (sql/format sqlmap) jdbc-opts))

(defn execute-one! [connectable sqlmap]
  (jdbc/execute-one! connectable (sql/format sqlmap) jdbc-opts))

(defn plan [connectable sqlmap]
  (jdbc/plan connectable (sql/format sqlmap) jdbc-opts))
