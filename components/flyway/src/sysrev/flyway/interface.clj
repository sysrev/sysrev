(ns sysrev.flyway.interface
  (:require [sysrev.flyway.core :as flyway]))

(defn migrate! [datasource]
  (flyway/migrate! datasource))
