(require 'hashp.core)

(ns sysrev.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [donut.system :as ds]
            [salmon.signal :as sig]
            [sysrev.aws-client.interface :as aws-client]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.listeners :as listeners]
            [sysrev.db.migration :as migration]
            [sysrev.file.s3 :as s3]
            [sysrev.localstack.interface :as localstack]
            [sysrev.memcached.interface :as mem]
            [sysrev.nrepl.interface :as nrepl]
            [sysrev.postgres.core :as pg]
            [sysrev.scheduler.core :as scheduler]
            [sysrev.secrets-manager.interface :as secrets-manager]
            [sysrev.sente :as sente]
            [sysrev.sysrev-api.main]
            [sysrev.sysrev-api.pedestal]
            [sysrev.system2.interface :as sys]
            [sysrev.util :as util]
            [sysrev.web.core :as web]))

(Thread/setDefaultUncaughtExceptionHandler
 (util/uncaught-exception-handler "DefaultUncaughtExceptionHandler"))

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

(defn localstack [{:keys [aws profile]}]
  (if (#{:dev :test} profile)
    (localstack/localstack aws)
    {}))

(defn aws-opts [{:keys [aws-access-key-id aws-region
                        aws-secret-access-key aws-session-token]}]
  (cond-> {:region (or aws-region "us-east-1")}
    (seq aws-access-key-id) (assoc :access-key-id aws-access-key-id)
    (seq aws-secret-access-key) (assoc :secret-access-key aws-secret-access-key)
    (seq aws-session-token) (assoc :session-token aws-session-token)))

(defonce system (atom nil))

(defn system-def [& {:keys [config postgres-overrides]}]
  (let [config (-> config :postgres
                   (merge postgres-overrides)
                   (->> (assoc config :postgres)))]
    {::ds/defs
     {:sysrev
      {:config (secrets-manager/transformer-component (aws-opts config) config)
       :localstack (sys/stuartsierra->ds (localstack config))
       :memcached (-> (sys/stuartsierra->ds
                       (cond
                         (:memcached config) (mem/client (:memcached config))
                         (:memcached-server config) (component/using
                                                     (mem/temp-client)
                                                     {:server :memcached-server})))
                      (assoc ::ds/resume (fn [{::ds/keys [instance]}]
                                           (mem/flush! instance)
                                           instance)
                             ::ds/suspend (fn [{::ds/keys [instance]}] instance)))
       :memcached-server (when (:memcached-server config)
                           (sys/stuartsierra->ds
                            (mem/temp-server (:memcached-server config))))
       :postgres (-> (sys/stuartsierra->ds
                      (component/using (pg/postgres) [:config]))
                     (assoc ::ds/resume (fn [{::ds/keys [instance]}]
                                          (pg/recreate-db! instance)
                                          instance)
                            ::ds/suspend (fn [{::ds/keys [instance]}] instance)))
       :postgres-run-after-start (sys/stuartsierra->ds
                                  (component/using
                                   (postgres-run-after-start)
                                   [:postgres]))
       :postgres-listener (let [{:as c ::ds/keys [start stop]}
                                (-> (listeners/listener)
                                    (component/using [:postgres :sente])
                                    sys/stuartsierra->ds)]
                            (assoc c ::ds/resume start ::ds/suspend stop))
       :s3 (sys/stuartsierra->ds
            (component/using
             (aws-client/aws-client
              :after-start s3/create-s3-buckets!
              :client-opts (assoc (:aws config) :api :s3))
             [:localstack]))
       :scheduler (sys/stuartsierra->ds
                   (component/using
                    (if (#{:test :remote-test} (:profile env))
                      (scheduler/mock-scheduler)
                      (scheduler/scheduler))
                    [:config :postgres :sr-context]))
       :sente (sys/stuartsierra->ds
               (component/using
                (sente/sente :receive-f sente/receive-sente-channel!)
                [:config :postgres]))
       ;; The :sr-context (Sysrev context) holds components that many functions need
       :sr-context (sys/stuartsierra->ds
                    (component/using
                     {}
                     [:config :memcached :postgres :s3 :sysrev-api-pedestal]))
       :sysrev-api-config (or (:sysrev-api-config config)
                              (sysrev.sysrev-api.main/get-config))
       :sysrev-api-pedestal (sys/stuartsierra->ds
                             (component/using
                              (sysrev.sysrev-api.pedestal/pedestal)
                              {:config :sysrev-api-config
                               :postgres :postgres}))
       :web-server (sys/stuartsierra->ds
                    (component/using
                     (web/web-server
                      :handler-f web/sysrev-handler
                      :port (-> config :server :port))
                     [:sente :sr-context]))}}}))

(defn start-non-global!
  "Start a system and return it without touching the sysrev.main/system atom."
  [& {:keys [config port-override postgres-overrides system-def-f]}]
  (log/info "Starting system")
  (let [config (cond-> (or config env)
                 port-override (assoc-in [:server :port] port-override))
        datapub (when (:datapub-embedded config)
                  (component/start
                   ((requiring-resolve 'datapub.main/datapub-system)
                    {:options {:load-fixtures? true}
                     :system-f
                     (fn []
                       (-> ((requiring-resolve 'datapub.main/get-config))
                           ((requiring-resolve 'datapub.main/system-map))))})))
        config (if datapub
                 (let [port (get-in datapub [:system :pedestal :bound-port])
                       graphql-endpoint (str "http://localhost:" port "/api")]
                   (-> config
                       (assoc :datapub-endpoint graphql-endpoint
                              :datapub-ws (str "ws://localhost:" port "/ws")
                              :graphql-endpoint graphql-endpoint)
                       (update :sysrev-api-config assoc
                               :graphql-endpoint graphql-endpoint
                               :sysrev-dev-key (:sysrev-dev-key config))))
                 config)
        system (-> ((or system-def-f system-def)
                    :config config
                    :postgres-overrides postgres-overrides)
                   (assoc-in [::ds/defs :datapub :system] (or datapub {}))
                   sig/start!)]
    (log/info "System started")
    system))

(defn start!
  "Start a system and assign it to the sysrev.main/system atom."
  [& {:keys [only-if-new port-override postgres-overrides]}]
  (when (or (not only-if-new) (nil? @system))
    (reset! system (start-non-global!
                    :port-override port-override
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

(comment
  ;; List active transactions
  (db/execute!
   (-> @system ::ds/instances :sysrev :sr-context)
   {:select [:pid [[:- [:now] :query-start] :duration]
             :query :state]
    :from :pg-stat-activity
    :where [:and [:= :state "active"]
            ;; Don't include self
            [:> [:- [:now] :query-start] [:interval "1 millisecond"]]]}))
