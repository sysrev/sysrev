(ns sysrev.job-runner.interface
  (:require [donut.system :as-alias ds]
            [sysrev.job-queue.interface :as jq]))

(declare process-job-queue!)

(defn- last-job-id-watcher [instance _ _ old-val new-val]
  (when (not= old-val new-val)
    ; Use a future to avoid blocking the thread setting
    ; the atom.
    (identity (process-job-queue! instance))))

(defn component
  "Returns a donut.system component that implements a job runner.

  config: {
    :args Extra args to pass to `f`.
    :f Function called to process jobs.
    :job-queue A [[sysrev.job-queue.interface/component]].
  }

  instance: {
    :args Extra args to pass to `f`.
    :f Function called to process jobs.
    :job-queue A [[sysrev.job-queue.interface/component]].
  }"
  [config]
  {::ds/config config
   ::ds/start
   (fn [{::ds/keys [instance] {:keys [args f job-queue]} ::ds/config}]
     (or instance
         (let [instance {:args args
                         :f f
                         :job-queue job-queue}]
           ; A primitive but effective way to process new jobs so they don't
           ; have to wait on polling to detect them.
           (add-watch (:last-job-id job-queue) ::last-job-id
                      (fn [k ref old-val new-val]
                        (last-job-id-watcher instance k ref old-val new-val)))
           instance)))
   ::ds/stop
   (fn [{::ds/keys [instance] {:keys []} ::ds/instance}]
     nil)})

(defn process-job-queue!
  "Processes up to 1 job from the job queue and calls
   `(f instance job)`."
  [{:keys [args f job-queue]}]
  (jq/process-job-queue!
   job-queue
   (fn [_job-queue job]
     (apply f job args))))

