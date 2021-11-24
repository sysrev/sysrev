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

(defn system-map [& {:keys [config postgres-overrides]}]
  (component/system-map
   :config config
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
               [:config :postgres])
   :sente (component/using
           (sente/sente :receive-f sente/receive-sente-channel!)
           [:config :postgres])
   :web-server (component/using
                (web/web-server
                 :handler-f web/sysrev-handler
                 :port (-> config :server :port))
                [:config :postgres :sente])))

(defn start-non-global!
  "Start a system and return it without touching the sysrev.main/system atom."
  [& {:keys [config postgres-overrides system-map-f]}]
  (log/info "Starting system")
  (let [config (or config env)
        datapub (when (:datapub-embedded config)
                  (component/start
                   ((requiring-resolve 'datapub.main/datapub-system)
                    {:options {:load-fixtures? true}
                     :system-f
                     (fn []
                       (-> ((requiring-resolve 'datapub.main/get-config))
                           ((requiring-resolve 'datapub.main/system-map))
                           (dissoc :s3)
                           (update :pedestal
                                   #(vary-meta % update ::component/dependencies
                                               dissoc :s3))))})))
        config (if datapub
                 (let [port (get-in datapub [:system :pedestal :bound-port])]
                   (assoc config
                          :datapub-api (str "http://localhost:" port "/api")
                          :datapub-ws (str "ws://localhost:" port "/ws")))
                 (assoc config
                        :datapub-api "https://www.datapub.dev/api"
                        :datapub-ws "wss://www.datapub.dev/ws"))
        system (-> ((or system-map-f system-map)
                    :config config
                    :postgres-overrides postgres-overrides)
                   (assoc :datapub (or datapub {}))
                   component/start)]
    (log/info "System started")
    system))

(defn start!
  "Start a system and assign it to the sysrev.main/system atom."
  [& {:keys [only-if-new postgres-overrides]}]
  (when (or (not only-if-new) (nil? @system))
    (reset! system (start-non-global!
                    :postgres-overrides postgres-overrides))))

(defn stop!
  "Stop the provided system or, by default, sysrev.main/system."
  [& [m]]
  (log/info "Stopping system")
  (let [system (if m
                 (component/stop m)
                 (swap! system component/stop))]
    (log/info "System stopped")
    system))

(defn -main []
  (start!))
