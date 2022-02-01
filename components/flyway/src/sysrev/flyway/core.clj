(ns sysrev.flyway.core
  (:import [org.flywaydb.core Flyway]
           [org.flywaydb.core.api.configuration FluentConfiguration]))

(defn migrate! [datasource file-locations]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      ^FluentConfiguration
      (.locations ^"[Ljava.lang.String;" (into-array file-locations))
      (.dataSource datasource)
      .load
      .migrate))
