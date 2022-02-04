(ns sysrev.sysrev-api.main
  (:require
   [clojure.edn :as edn]
   [com.stuartsierra.component :as component]
   [sysrev.config.interface :as config]
   [sysrev.nrepl.interface :as nrepl]
   [sysrev.postgres.interface :as pg]
   [sysrev.sysrev-api.pedestal :as pedestal]))

(def envs #{:dev :prod :staging :test})

(def defaults
  {:postgres {:dbtype "postgres"
              :flyway-locations ["classpath:/sysrev-api/flyway"]}})

(defn deep-merge [& args]
  (if (every? #(or (map? %) (nil? %)) args)
    (apply merge-with deep-merge args)
    (last args)))

(defn get-config []
  (let [{:keys [system-config-file] :as config} (config/get-config "sysrev-api-config.edn")
        local-config (when system-config-file
                       (edn/read-string (slurp system-config-file)))]
    (deep-merge defaults config local-config)))

(defn system-map [{:keys [aws env] :as config}]
  (if-not (envs env)
    (throw (ex-info
            (if env ":env unrecognized" ":env missing")
            {:allowed-envs envs
             :env env}))
    (component/system-map
     :config config
     :pedestal (component/using
                (pedestal/pedestal)
                [:config :postgres])
     :postgres (component/using
                (pg/postgres)
                [:config]))))

(defonce system (atom nil))

(defn start! [& [config]]
  (swap! system #(component/start (or % (system-map
                                         (or config (get-config)))))))

(defn stop! []
  (swap! system component/stop))

(defn reload! [& [config]]
  (swap! system #(do (when % (component/stop %))
                     (component/start (system-map (or config (get-config)))))))

(defonce nrepl (atom nil))

(defn start-nrepl! [config]
  (swap! nrepl #(component/start
                 (or % (component/system-map :nrepl (nrepl/map->NRepl {:config config}))))))

(defn -main []
  (let [config (get-config)]
    (start-nrepl! config)
    (start! config)))

(defrecord SysrevAPISystem [system system-f]
  component/Lifecycle
  (start [this]
    (if system
      this
      (assoc this :system (component/start
                           (if system-f
                             (system-f)
                             (system-map (get-config)))))))
  (stop [this]
    (if-not system
      this
      (do (component/stop system)
          (assoc this :system nil)))))

(defn sysrev-api-system [{:keys [system-f]}]
  (map->SysrevAPISystem {:system-f system-f}))
