(ns datapub.flyway
  (:import [org.flywaydb.core Flyway]
           [org.flywaydb.core.api.configuration FluentConfiguration]))

(defn migrate! [datasource]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      ^FluentConfiguration
      (.locations ^"[Ljava.lang.String;" (into-array ["classpath:/datapub/flyway"]))
      (.dataSource datasource)
      .load
      .migrate))
