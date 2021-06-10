(ns sysrev.flyway.core
  (:import [org.flywaydb.core Flyway]))

(set! *warn-on-reflection* true)

(defn migrate! [datasource]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      (.dataSource datasource)
      .load
      .migrate))
