(ns sysrev.flyway.core
  (:import [org.flywaydb.core Flyway]
           [org.flywaydb.core.api.configuration FluentConfiguration]))

(set! *warn-on-reflection* true)

(defn migrate! [datasource]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      ^FluentConfiguration
      (.locations ^"[Ljava.lang.String;" (into-array ["classpath:/sql"]))
      (.dataSource datasource)
      .load
      .migrate))
