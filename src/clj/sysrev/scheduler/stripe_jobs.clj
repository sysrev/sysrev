(ns sysrev.scheduler.stripe-jobs
  (:require [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-hours]]
            [clojurewerkz.quartzite.triggers :as t]
            [sysrev.payment.stripe :as stripe]))

(defrecord PlansJob []
  org.quartz.Job
  (execute [_ _]
    (stripe/update-stripe-plans-table)
    (stripe/update-subscriptions)))

(defn schedule-plans-job [scheduler]
  (let [job (j/build
              (j/of-type PlansJob)
              (j/with-identity (j/key "jobs.stripe-plans-job.1")))
        trigger (t/build
                  (t/with-identity (t/key "triggers.stripe-plans-job.1"))
                  (t/start-now)
                  (t/with-schedule (schedule
                                     (repeat-forever)
                                     (with-interval-in-hours 24))))]
    (q/schedule scheduler job trigger)))
 
(defrecord SubscriptionsJob []
  org.quartz.Job
  (execute [_ _]
    (stripe/update-subscriptions)))

(defn schedule-subscriptions-job [scheduler]
  (let [job (j/build
              (j/of-type PlansJob)
              (j/with-identity (j/key "jobs.stripe-subscription-job.1")))
        trigger (t/build
                  (t/with-identity (t/key "triggers.stripe-subscription-job.1"))
                  (t/start-now)
                  (t/with-schedule (schedule
                                     (repeat-forever)
                                     (with-interval-in-hours 24))))]
    (q/schedule scheduler job trigger)))
 
