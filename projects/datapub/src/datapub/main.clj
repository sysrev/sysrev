(ns datapub.main
  (:require hashp.core ; Load #p data reader
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [datapub.pedestal :as pedestal]
            [datapub.postgres :as postgres]))

(def envs #{:dev :prod :staging :test})

(defn get-config []
  (let [embedded? (boolean (System/getenv "DBEMBEDDED"))]
    {:env (some-> (System/getenv "ENV") str/lower-case keyword)
     :pedestal {:port (or (System/getenv "PORT") 8888)
                :sysrev-dev-key (System/getenv "SYSREV_DEV_KEY")}
     :postgres {:create-if-not-exists? true
                :embedded? embedded?
                :dbname (or (System/getenv "DBNAME") "datapub")
                :dbtype "postgres"
                :host (System/getenv "DBHOST")
                :password (System/getenv "DBPASSWORD")
                :port (or (System/getenv "DBPORT")
                          (if embedded? 0 5432))
                :user (or (System/getenv "DBUSER") "postgres")}}))

(defn system-map [{:keys [env] :as config}]
  (if-not (envs env)
    (throw (ex-info
            (if env ":env unrecognized" ":env missing")
            {:allowed-envs envs
             :env env}))
    (component/system-map
     :config config
     :pedestal (component/using
                (pedestal/pedestal {:config (assoc (:pedestal config) :env env)})
                [:postgres])
     :postgres (postgres/postgres {:config (:postgres config)}))))

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
