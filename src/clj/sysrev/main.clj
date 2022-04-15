(ns sysrev.main
  (:gen-class)
  (:require
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [sysrev.aws-client.interface :as aws-client]
   [sysrev.config :refer [env]]
   [sysrev.db.core :as db]
   [sysrev.db.listeners :as listeners]
   [sysrev.db.migration :as migration]
   [sysrev.file.s3 :as s3]
   [sysrev.nrepl.interface :as nrepl]
   [sysrev.postgres.core :as pg]
   [sysrev.scheduler.core :as scheduler]
   [sysrev.sente :as sente]
   [sysrev.sysrev-api.main]
   [sysrev.sysrev-api.pedestal]
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
   :config (-> config :postgres
               (merge postgres-overrides)
               (->> (assoc config :postgres)))
   :postgres (component/using (pg/postgres) [:config])
   :postgres-run-after-start (component/using
                              (postgres-run-after-start)
                              [:postgres])
   :postgres-listener (component/using
                       (listeners/listener)
                       [:postgres :sente])
   :s3 (aws-client/aws-client
        :after-start s3/create-s3-buckets!
        :client-opts (assoc (:aws config) :api :s3))
   :scheduler (component/using
               (if (#{:test :remote-test} (:profile env))
                 (scheduler/mock-scheduler)
                 (scheduler/scheduler))
               [:config :postgres])
   :sente (component/using
           (sente/sente :receive-f sente/receive-sente-channel!)
           [:config :postgres])
   ;; The :sr-context (Sysrev context) holds components that many functions need
   :sr-context (component/using
                {}
                [:config :postgres :sysrev-api-pedestal])
   :sysrev-api-config (-> (or (:sysrev-api-config config)
                              (sysrev.sysrev-api.main/get-config))
                          (assoc :get-tx (fn [_] (:connection db/*conn*))))
   :sysrev-api-pedestal (component/using
                         (sysrev.sysrev-api.pedestal/pedestal)
                         {:config :sysrev-api-config
                          :postgres :postgres})
   :web-server (component/using
                (web/web-server
                 :handler-f web/sysrev-handler
                 :port (-> config :server :port))
                [:sente :sr-context])))

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
                           ((requiring-resolve 'datapub.main/system-map))))})))
        config (if datapub
                 (let [port (get-in datapub [:system :pedestal :bound-port])]
                   (assoc config
                          :datapub-api (str "http://localhost:" port "/api")
                          :datapub-ws (str "ws://localhost:" port "/ws")))
                 config)
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

(defn reload!
  "Reload sysrev.main system, but reuse the postgres component without
  reloading it."
  []
  (swap! system
         (fn [system]
           (when system (component/stop system))
           (start-non-global!))))

(defn reload-with-fixtures! []
  (reload!)
  ((requiring-resolve 'sysrev.fixtures.interface/load-fixtures!)))

(defonce nrepl (atom nil))

(defn start-nrepl! [config]
  (swap! nrepl #(component/start
                 (or % (component/system-map :nrepl (nrepl/map->NRepl {:config config}))))))

(defn -main []
  (start-nrepl! env)
  (start!))
