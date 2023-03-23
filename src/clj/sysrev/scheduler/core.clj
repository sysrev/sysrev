(ns sysrev.scheduler.core
  (:require [clojurewerkz.quartzite.scheduler :as qs]
            [com.stuartsierra.component :as component]
            [sysrev.scheduler.gpt :as gpt]
            [sysrev.scheduler.job-queue :as job-queue]
            [sysrev.scheduler.stripe-jobs :refer [schedule-plans-job schedule-subscriptions-job]]
            [sysrev.scheduler.living-data-sources :refer [schedule-living-data-sources]]))

(defrecord Scheduler [quartz-scheduler schedule-f]
  component/Lifecycle
  (start [this]
    (if quartz-scheduler
      this
      (let [qs (qs/initialize)
            this (assoc this :quartz-scheduler qs)]
        (schedule-f this)
        (qs/start qs)
        this)))
  (stop [this]
    (if-not quartz-scheduler
      this
      (do
        (qs/shutdown quartz-scheduler)
        (assoc this :quartz-scheduler nil)))))

(defn scheduler []
  (map->Scheduler {:schedule-f
                   (fn [scheduler]
                     (gpt/job scheduler)
                     (job-queue/job scheduler)
                     (schedule-plans-job scheduler)
                     (schedule-subscriptions-job scheduler)
                     (schedule-living-data-sources scheduler))}))


(defn mock-scheduler []
  (map->Scheduler {:schedule-f :noop}))

