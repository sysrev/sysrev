(ns sysrev.scheduler.living-data-sources
  (:require [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-hours]]
            [clojurewerkz.quartzite.triggers :as t]
            [honeysql.helpers :as sqlh :refer [select from where]]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.source.core :as source]
            [sysrev.source.ctgov :as ctgov]
            [sysrev.source.project-filter :as project-filter]
            [sysrev.source.pubmed :as pubmed]))

(defn check-new-articles-ctgov [{:keys [source-id] :as source} & {:keys [config]}]
  (let [new-article-ids (ctgov/get-new-articles-available source :config config)
        project-id (source/source-id->project-id source-id)]
    (source/set-new-articles-available project-id source-id (count new-article-ids))))

(defn check-new-articles-project-filter [{:keys [source-id] :as source}]
  (let [new-article-ct (project-filter/count-new-articles-available source)
        project-id (source/source-id->project-id source-id)]
    (source/set-new-articles-available project-id source-id new-article-ct)
    new-article-ct))

(defn check-new-articles-pubmed [{:keys [source-id] :as source}]
  (let [new-article-ids (pubmed/get-new-articles-available source)
        project-id (source/source-id->project-id source-id)]
    (source/set-new-articles-available project-id source-id (count new-article-ids))))

(defn check-new-articles [& {:keys [config]}]
  (let [sources (-> (select :*)
                    (from [:project-source :psrc])
                    (where [:= :psrc.check-new-results true]
                           [:= :psrc.enabled true])
                    do-query)]
    (doseq [source sources]
      (let [source-type (-> source :meta :source)]
        (Thread/sleep 5000)
        (case source-type
          "CT.gov search" (check-new-articles-ctgov source :config config)
          "Project Filter" (check-new-articles-project-filter source)
          "PubMed search" (check-new-articles-pubmed source)
          :noop)))))

#_:clj-kondo/ignore
(j/defjob LivingDataSourcesJob [ctx]
  (check-new-articles :config (get (qc/from-job-data ctx) "config")))

#_:clj-kondo/ignore
(defn schedule-living-data-sources [{:keys [config quartz-scheduler]}]
  (let [job (j/build
              (j/of-type LivingDataSourcesJob)
              (j/using-job-data {:config config})
              (j/with-identity (j/key "jobs.living-data-sources.1")))
        trigger (t/build
                  (t/with-identity (t/key "triggers.living-data-sources.1"))
                  (t/start-now)
                  (t/with-schedule (schedule
                                     (repeat-forever)
                                     (with-interval-in-hours 24))))]
    (q/schedule quartz-scheduler job trigger)))
 
