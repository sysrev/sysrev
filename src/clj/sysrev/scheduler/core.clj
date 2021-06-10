(ns sysrev.scheduler.core
  (:require [clojurewerkz.quartzite.scheduler :as q]
            [sysrev.scheduler.living-data-sources :refer [schedule-living-data-sources]]))

(defonce scheduler (atom nil))

(defn stop-scheduler []
  (q/shutdown @scheduler))

(defn start-scheduler []
  (when @scheduler
    (stop-scheduler))
  (reset! scheduler (q/start (q/initialize)))

  (schedule-living-data-sources @scheduler))