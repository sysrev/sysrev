(ns sysrev.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.listeners :as listeners]
            [sysrev.db.migration :as migration]
            [sysrev.postgres.core :as postgres]
            [sysrev.project.core :as project]
            [sysrev.scheduler.core :as scheduler]
            [sysrev.sente :as sente]
            [sysrev.web.core :as web]))

(defrecord PostgresRunAfterStart [done?]
  component/Lifecycle
  (start [this]
    (if done?
      this
      (do
        (db/set-active-db! (:postgres this))
        (when-not (#{:remote-test :test} (:profile env))
          (migration/ensure-updated-db))
        (project/cleanup-browser-test-projects)
        (assoc this :done? true))))
  (stop [this]
    (if done?
      (assoc this :done? false)
      this)))

(defn postgres-run-after-start []
  (map->PostgresRunAfterStart {:done? false}))

(defonce system (atom nil))

(defn system-map [& {:keys [postgres-overrides]}]
  (component/system-map
   :postgres (postgres/postgres postgres-overrides)
   :postgres-run-after-start (component/using
                              (postgres-run-after-start)
                              [:postgres])
   :postgres-listener (component/using
                       (listeners/listener)
                       [:postgres :sente])
   :scheduler (component/using
                (if (#{:test :remote-test} (:profile env))
                  (scheduler/mock-scheduler)
                  (scheduler/scheduler))
               [:postgres])
   :sente (component/using
           (sente/sente :receive-f sente/receive-sente-channel!)
           [:postgres])
   :web-server (component/using
                (web/web-server
                 :handler-f web/sysrev-handler
                 :port (-> env :server :port))
                [:postgres :sente])))

(defn start-system! [& {:keys [only-if-new postgres-overrides]}]
  (when (or (not only-if-new) (nil? @system))
    (log/info "Starting system")
    (->> (system-map :postgres-overrides postgres-overrides)
         component/start
         (reset! system))
    (log/info "System started")))

(defn stop-system! []
  (log/info "Stopping system")
  (swap! system component/stop)
  (log/info "System stopped"))

(defn -main []
  (start-system!))
