(ns sysrev.scheduler.job-queue
  (:require [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-seconds]]
            [clojurewerkz.quartzite.triggers :as t]
            [sysrev.job-queue.interface :as jq]
            [sysrev.export.core :as export]
            [sysrev.source.files :as files]))

(def job-type->fn
  {"generate-project-export" export/generate-project-export!
   "import-files" files/import-files!
   "import-project-source-articles" files/import-project-source-articles!})

(defn run-job [{:job/keys [id payload type]} sr-context]
  ((job-type->fn type) sr-context id payload))

(defn run [{:as sr-context :keys [job-queue]}]
  (jq/process-job-queue!
   job-queue
   (fn [_ job]
     (run-job job sr-context))))

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
                                   (with-interval-in-seconds 60))))]
    (q/schedule quartz-scheduler job trigger)))
