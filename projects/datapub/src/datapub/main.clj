(ns datapub.main
  (:require hashp.core ; Load #p data reader
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [datapub.file :as file]
            [datapub.pedestal :as pedestal]
            [datapub.postgres :as postgres]
            [datapub.secrets-manager :as secrets-manager]
            [medley.core :as medley]
            [sysrev.config.interface :as config]))

(def envs #{:dev :prod :staging :test})

(defrecord Secrets [])

(defmethod clojure.core/print-method Secrets
  [system ^java.io.Writer writer]
  (.write writer "#<Secrets>"))

(defn resolve-secrets [m secrets-manager]
  (medley/map-vals
   #(if (map? %)
      (if (:secrets-manager/arn %)
        (secrets-manager/get-config-secret secrets-manager %)
        (resolve-secrets % secrets-manager))
      %)
   m))

(defrecord Config [secrets secrets-manager]
  component/Lifecycle
  (start [this]
    (update this :secrets resolve-secrets secrets-manager))
  (stop [this]
    this))

(defn get-config []
  (-> (config/get-config "datapub-config.edn")
      (update :secrets map->Secrets)))

(defn system-map [{:keys [aws env] :as config}]
  (if-not (envs env)
    (throw (ex-info
            (if env ":env unrecognized" ":env missing")
            {:allowed-envs envs
             :env env}))
    (component/system-map
     :config (component/using
              (map->Config config)
              [:secrets-manager])
     :pedestal (component/using
                (pedestal/pedestal)
                [:config :postgres :s3 :secrets-manager])
     :postgres (component/using
                (postgres/postgres)
                [:config])
     :s3 (component/using (file/s3-client aws) [:config])
     :secrets-manager (secrets-manager/client))))

(defonce system (atom nil))

(defn start! [& [config]]
  (swap! system #(component/start (or % (system-map
                                         (or config (get-config)))))))

(defn stop! []
  (swap! system component/stop))

(defn reload! [& [config]]
  (swap! system #(do (when % (component/stop %))
                     (component/start (system-map (or config (get-config)))))))

(defn reload-with-fixtures! [& [config]]
  (let [system (swap! system #(do (when % (component/stop %))
                                  (component/start
                                   (system-map (or config (get-config))))))]
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
