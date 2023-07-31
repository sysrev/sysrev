(ns sysrev.scheduler.trim-web-events
  (:require [clojure.tools.logging :as log]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-hours]]
            [clojurewerkz.quartzite.triggers :as t]
            [sysrev.db.core :as db]))

(defn run
  "Trim web-event to keep it small enough to scan."
  [sr-context]
  (log/info "Trimming web-events more than 2 weeks old")
  (db/with-long-tx [sr-context sr-context]
    (db/execute-one!
     sr-context
     {:delete-from :web-event
      :where [:< :event-time [:- [:now] [:interval "2 weeks"]]]}))
  (log/info "Finished trimming web-events"))

#_:clj-kondo/ignore
(j/defjob Job [ctx]
  (run (get (qc/from-job-data ctx) "sr-context")))

#_:clj-kondo/ignore
(defn job [{:keys [quartz-scheduler sr-context]}]
  (let [job (j/build
             (j/of-type Job)
             (j/using-job-data {:sr-context sr-context})
             (j/with-identity (j/key "jobs.trim-web-event.1")))
        trigger (t/build
                 (t/with-identity (t/key "triggers.trim-web-event.1"))
                 (t/start-now)
                 (t/with-schedule (schedule
                                   (repeat-forever)
                                   (with-interval-in-hours 24))))]
    (q/schedule quartz-scheduler job trigger)))
