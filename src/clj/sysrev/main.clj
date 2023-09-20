(require 'hashp.core)

(ns sysrev.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [medley.core :as medley]
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

(defn system-map [& {:keys [config postgres-overrides]}]
  (let [config (-> config :postgres
                   (merge postgres-overrides)
                   (->> (assoc config :postgres)
                        (secrets-manager/transform-secrets (aws-opts config))))]
    (->> {:config config
          :localstack (localstack config)
          :memcached (cond
                       (:memcached config) (mem/client (:memcached config))
                       (:memcached-server config) (component/using
                                                   (mem/temp-client)
                                                   {:server :memcached-server}))
          :memcached-server (when (:memcached-server config)
                              (mem/temp-server (:memcached-server config)))
          :postgres (component/using (pg/postgres) [:config])
          :postgres-run-after-start (component/using
                                     (postgres-run-after-start)
                                     [:postgres])
          :postgres-listener (component/using
                              (listeners/listener)
                              [:postgres :sente])
          :s3 (component/using
               (aws-client/aws-client
                :after-start s3/create-s3-buckets!
                :client-opts (assoc (:aws config) :api :s3))
               [:localstack])
          :scheduler (component/using
                      (if (#{:test :remote-test} (:profile env))
                        (scheduler/mock-scheduler)
                        (scheduler/scheduler))
                      [:config :postgres :sr-context])
          :sente (component/using
                  (sente/sente :receive-f sente/receive-sente-channel!)
                  [:config :postgres])
          ;; The :sr-context (Sysrev context) holds components that many functions need
          :sr-context (component/using
                       {}
                       [:config :memcached :postgres :s3 :sysrev-api-pedestal])
          :sysrev-api-config (or (:sysrev-api-config config)
                                 (sysrev.sysrev-api.main/get-config))
          :sysrev-api-pedestal (component/using
                                (sysrev.sysrev-api.pedestal/pedestal)
                                {:config :sysrev-api-config
                                 :postgres :postgres})
          :web-server (component/using
                       (web/web-server
                        :handler-f web/sysrev-handler
                        :port (-> config :server :port))
                       [:sente :sr-context])}
         (medley/remove-vals nil?)
         (mapcat seq)
         (apply component/system-map))))

(defn start-non-global!
  "Start a system and return it without touching the sysrev.main/system atom."
  [& {:keys [config port-override postgres-overrides system-map-f]}]
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
        system (-> ((or system-map-f system-map)
                    :config config
                    :postgres-overrides postgres-overrides)
                   (assoc :datapub (or datapub {}))
                   component/start)]
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
   (:sr-context @system)
   {:select [:pid [[:- [:now] :query-start] :duration]
             :query :state]
    :from :pg-stat-activity
    :where [:and [:= :state "active"]
            ;; Don't include self
            [:> [:- [:now] :query-start] [:interval "1 millisecond"]]]}))
