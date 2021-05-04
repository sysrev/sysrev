(ns sysrev.scheduler.living-data-sources
  (:require [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-hours]]
            [clojurewerkz.quartzite.triggers :as t]
            [sysrev.source.pubmed :as pubmed]
            [sysrev.source.core :as source]
            [honeysql.helpers :as sqlh :refer [select from where]]
            [sysrev.db.core :as db :refer [do-query]]))

(defn check-new-articles-pubmed [{:keys [source-id] :as source}]
  (let [new-article-ids (pubmed/get-new-articles-available source)
        project-id (source/source-id->project-id source-id)]
    (source/set-new-articles-available project-id source-id (count new-article-ids))))

(defn check-new-articles []
  (let [sources (-> (select :*)
                    (from [:project-source :psrc])
                    (where [:= :psrc.check-new-results true]
                           [:= :psrc.enabled true])
                    do-query)]
    (doseq [source sources]
      (let [source-type (-> source :meta :source)]
        (Thread/sleep 5000)
        (case source-type
          "PubMed search" (check-new-articles-pubmed source)
          :noop)))))

(defrecord LivingDataSourcesJob []
  org.quartz.Job
  (execute [_ _]
    (check-new-articles)))

(defn schedule-living-data-sources [scheduler]
  (let [job (j/build
              (j/of-type LivingDataSourcesJob)
              (j/with-identity (j/key "jobs.living-data-sources.1")))
        trigger (t/build
                  (t/with-identity (t/key "triggers.living-data-sources.1"))
                  (t/start-now)
                  (t/with-schedule (schedule
                                     (repeat-forever)
                                     (with-interval-in-hours 24))))]
    (q/schedule scheduler job trigger)))
 
