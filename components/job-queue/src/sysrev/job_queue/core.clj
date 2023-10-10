(ns sysrev.job-queue.core
  (:require [donut.system :as-alias ds]
            [sysrev.postgres.interface :as pg])
  (:import [java.util.concurrent Semaphore]))

(def default-config
  {:job-concurrency 8})

(defn shut-down-semaphore! [^Semaphore semaphore]
  (.reducePermits semaphore Integer/MAX_VALUE)
  (let [threads (.getQueuedThreads semaphore)]
    (doseq [thread threads]
      (.interrupt thread))
    (doseq [thread threads]
      (.join thread))))

(defn update-failed-jobs! [instance]
  (pg/with-tx [instance instance]
    (->> {:update :job
          :set {:status "failed"}
          :where [:and
                  [:= :status "started"]
                  [:>= :retries :max-retries]
                  [:not= nil :started-at]
                  [:>= [:now] [:+ :timeout [:coalesce :last-updated :started-at]]]]}
         (pg/execute-one! instance))))

(def get-job-q
  {:select :*
   :from :job
   :for [:update :job :skip-locked]
   :limit 1
   :where [:or
           [:= :status "new"]
           [:and
            [:= :status "failed"]
            [:< :retries :max-retries]]]})

(defn select-job! [instance]
  (pg/with-tx [instance instance]
    (when-let [job (or (pg/execute-one! instance get-job-q)
                       (do (update-failed-jobs! instance)
                           (pg/execute-one! instance get-job-q)))]
      (->> {:update :job
            :set {:last-updated [:now]
                  :retries [:+ 1 :retries]
                  :started-at [:now]
                  :status "started"}
            :where [:= :id (:job/id job)]}
           (pg/execute-one! instance))
      job)))
