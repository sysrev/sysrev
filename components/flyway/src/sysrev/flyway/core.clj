(ns sysrev.flyway.core
  (:import [org.flywaydb.core Flyway]))

(set! *warn-on-reflection* true)

(defn migrate! [datasource]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      (.locations (into-array ["filesystem:./resources/sql" "filesystem:./sql"]))
      (.dataSource datasource)
      .load
      .migrate))
