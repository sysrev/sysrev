(ns sysrev.spark.core
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [flambo.api :as f]
            [flambo.conf :as fc]
            [flambo.tuple :as ft]
            [flambo.sql :as fsql]
            [honeysql.core :as sql]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db])
  (:import [org.apache.spark.sql SQLContext SparkSession Dataset]
           [org.apache.spark SparkConf SparkContext]
           [java.util Properties]))

(defonce spark-conf (atom nil))
(defonce spark-context (atom nil))
(defonce sql-context (atom nil))
(defonce spark-session (atom nil))
(defonce spark-db-config (atom {}))

(defn set-spark-db-config!
  ([]
   (set-spark-db-config! (:postgres env)))
  ([{:keys [dbname user password host port] :as opts}]
   (swap! spark-db-config merge opts)
   (swap! spark-db-config assoc
          :props
          (let [^Properties props (Properties.)]
            (-> props (.put "driver" "org.postgresql.Driver"))
            (-> props (.put "user" (:user @spark-db-config)))
            (when-let [pw (:password @spark-db-config)]
              (-> props (.put "password" pw)))
            props)
          :jdbc
          (format "jdbc:postgresql://%s:%d/%s" host port dbname))))

(defn init-spark []
  (when @spark-context
    (.stop @spark-context))
  (when @spark-session
    (.stop @spark-session))
  (reset! spark-conf (-> (fc/spark-conf)
                         (fc/master "local[*]")
                         (fc/app-name "sysrev-clj")))
  (reset! spark-context (-> @spark-conf
                            (f/spark-context)))
  (reset! sql-context (-> @spark-context
                          (fsql/sql-context)))
  (reset! spark-session (-> (SparkSession/builder)
                            (.config @spark-conf)
                            (.getOrCreate)))
  (set-spark-db-config!))

(defn ^Dataset df-query
  "Creates a SQL query on Postgres using the active SparkSession, returning a
  DataFrame.

  `sql` is the query and can be a string or a honeysql query map.
  `tname` is a name for the DataFrame table that will hold the results."
  [sql tname]
  (let [sql (if (string? sql) sql
                (db/to-sql-string sql))
        sql (format "(%s) as %s" sql tname)
        {:keys [jdbc props]} @spark-db-config]
    (-> @spark-session
        (.read)
        (.jdbc jdbc sql props))))

(defn df->clj
  "Convert a Spark DataFrame into a Clojure data structure (sequence of row vectors)."
  [^Dataset df]
  (->> df
       (.collectAsList)
       (map fsql/row->vec)))

(defn maps-to-df
  "Converts a sequence of maps into a DataFrame, by converting each map to JSON
  and passing the sequence into flambo.sql/json-rdd to create a DataFrame."
  [json-maps]
  (->> json-maps
       (map json/write-str)
       (f/parallelize @spark-context)
       (fsql/json-rdd @sql-context)))
