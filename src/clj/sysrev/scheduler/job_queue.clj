(ns sysrev.scheduler.job-queue
  (:require [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-minutes]]
            [clojurewerkz.quartzite.triggers :as t]
            [sysrev.source.files :as files]))

(defn run [sr-context]
  (files/import-from-job-queue! sr-context))

#_:clj-kondo/ignore
(j/defjob Job [ctx]
  (run (get (qc/from-job-data ctx) "sr-context")))

#_:clj-kondo/ignore
(defn job [{:keys [quartz-scheduler sr-context]}]
  (let [job (j/build
             (j/of-type Job)
             (j/using-job-data {:sr-context sr-context})
             (j/with-identity (j/key "jobs.job-queue.1")))
        trigger (t/build
                 (t/with-identity (t/key "triggers.job-queue.1"))
                 (t/start-now)
                 (t/with-schedule (schedule
                                   (repeat-forever)
                                   (with-interval-in-minutes 1))))]
    (q/schedule quartz-scheduler job trigger)))
