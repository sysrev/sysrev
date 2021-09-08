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
               [:postgres])
   :sente (component/using
           (sente/sente :receive-f sente/receive-sente-channel!)
           [:config :postgres])
   :web-server (component/using
                (web/web-server
                 :handler-f web/sysrev-handler
                 :port (-> env :server :port))
                [:config :postgres :sente])))

(defn start-system! [& {:keys [only-if-new postgres-overrides]}]
  (when (or (not only-if-new) (nil? @system))
    (log/info "Starting system")
    (let [datapub (when (:datapub-embedded env)
                    ((requiring-resolve 'datapub.main/reload-with-fixtures!)))
          config (if datapub
                   (let [port (get-in datapub [:pedestal :bound-port])]
                     (assoc env
                            :datapub-api (str "http://localhost:" port "/api")
                            :datapub-ws (str "ws://localhost:" port "/ws")))
                   (assoc env
                          :datapub-api "https://www.datapub.dev/api"
                          :datapub-ws "wss://www.datapub.dev/ws"))]
      (->> (system-map
            :config config
            :postgres-overrides postgres-overrides)
           component/start
           (reset! system)))
    (log/info "System started")))

(defn stop-system! []
  (log/info "Stopping system")
  (swap! system component/stop)
  (when (:datapub-embedded env)
    ((requiring-resolve 'datapub.main/stop!)))
  (log/info "System stopped"))

(defn -main []
  (start-system!))
