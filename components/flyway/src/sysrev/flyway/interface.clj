(ns sysrev.flyway.interface
  (:require
   [sysrev.flyway.core :as core]))

(defn migrate! [datasource file-locations]
  (core/migrate! datasource file-locations))
