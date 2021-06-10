(ns sysrev.flyway.core
  (:import [org.flywaydb.core Flyway]))

(set! *warn-on-reflection* true)

(defn migrate! [datasource]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      (.locations (into-array ["classpath:/sql"]))
      (.dataSource datasource)
      .load
      .migrate))
