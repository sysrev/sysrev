(ns sysrev.job-queue.interface
  (:require [donut.system :as-alias ds]
            [sysrev.job-queue.core :as core]
            [sysrev.postgres.interface :as pg])
  (:import [java.util.concurrent Semaphore]))

(defn component
  "Returns a donut.system component that implements a job queue.

   config: {
     :datasource Postgres JDBC datasource
     :job-concurrency Integer number of concurrent jobs allowed. Default: 8
   }

   instance: {
     :datasource Postgres JDBC datasource
     :last-job-id An atom containing the id of the last job created.
       [[clojure.core/add-watch]] to react to new jobs.
     :semaphore Semaphore acquired by jobs
   }"
  [config]
  {::ds/config (merge core/default-config config)
   ::ds/start
   (fn [{::ds/keys [instance] {:keys [datasource job-concurrency]} ::ds/config}]
     (or instance
         {:datasource datasource
          :last-job-id (atom nil)
          :semaphore (Semaphore. job-concurrency)}))
   ::ds/stop
   (fn [{::ds/keys [instance] {:keys [semaphore]} ::ds/instance}]
     (when instance
       (core/shut-down-semaphore! semaphore)
       nil))})

(defn create-job!
  "Creates a job and returns the job id.

   instance - An instance of [[component]]"
  [{:as instance :keys [last-job-id]}
   type payload
   & {:keys [max-retries started-at status]
      :or {max-retries 3
           started-at :%now
           status "new"}}]
  (pg/with-tx [instance instance]
    (->> {:insert-into :job
          :returning :id
          :values [{:max-retries max-retries
                    :payload (pg/jsonb-pgobject payload)
                    :started-at started-at
                    :status status
                    :type type}]}
         (pg/execute-one! instance)
         :job/id
         (reset! last-job-id))))

(defn process-job-queue!
  "Processes up to 1 job from the job queue and calls
   `(f instance job)`."
  [{:as instance :keys [semaphore]} f]
  (try
    (.acquire semaphore)
    (when-let [{:as job :job/keys [id]} (core/select-job! instance)]
      (f instance job)
      (pg/with-tx [instance instance]
        (->> {:update :job
              :set {:status "done"}
              :where [:= :id id]}
             (pg/execute-one! instance))))
    (finally
      (.release semaphore))))
