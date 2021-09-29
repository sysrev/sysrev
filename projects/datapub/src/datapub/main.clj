(ns datapub.main
  (:require hashp.core ; Load #p data reader
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [datapub.file :as file]
            [datapub.pedestal :as pedestal]
            [datapub.postgres :as postgres]
            [sysrev.config.interface :as config]))

(def envs #{:dev :prod :staging :test})

(defn get-config []
  (config/get-config "datapub-config.edn"))

(defn system-map [{:keys [aws env] :as config}]
  (if-not (envs env)
    (throw (ex-info
            (if env ":env unrecognized" ":env missing")
            {:allowed-envs envs
             :env env}))
    (component/system-map
     :config config
     :pedestal (component/using
                (pedestal/pedestal {:opts (assoc (:pedestal config) :env env)})
                [:config :postgres :s3])
     :postgres (postgres/postgres {:config (:postgres config)})
     :s3 (component/using (file/s3-client aws) [:config]))))

(defonce system (atom nil))

(defn start! []
  (swap! system #(component/start (or % (system-map (get-config))))))

(defn stop! []
  (swap! system component/stop))

(defn reload! []
  (swap! system #(do (when % (component/stop %))
                     (component/start (system-map (get-config))))))

(defn reload-with-fixtures! []
  (let [system (swap! system #(do (when % (component/stop %))
                                  (component/start (system-map (get-config)))))]
    ((requiring-resolve 'datapub.test/load-ctgov-dataset!) system)
    system))

(defn -main []
  (start!))

(defrecord DatapubSystem [options system system-f]
  component/Lifecycle
  (start [this]
    (if system
      this
      (let [system (component/start
                    (if system-f
                      (system-f)
                      (system-map (get-config))))]
        (when (:load-fixtures? options)
          ((requiring-resolve 'datapub.test/load-ctgov-dataset!) system))
        (assoc this :system system))))
  (stop [this]
    (if-not system
      this
      (do (component/stop system)
          (assoc this :system nil)))))

(defn datapub-system [{:keys [options system-f]}]
  (map->DatapubSystem {:options options :system-f system-f}))
