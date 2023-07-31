(ns sysrev.postgres.embedded
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [sysrev.contajners.interface :as con]
            [sysrev.contajners.interface.config :as conc]
            [sysrev.shutdown.interface :as shut]
            [sysrev.util-lite.interface :as ul]))

(defn container-config [image port]
  {:pre [(seq image) port]}
  (cond-> {:Env ["POSTGRES_HOST_AUTH_METHOD=trust"]
           :HostConfig {:AutoRemove true}
           :Image image}
    (conc/linux?) (conc/add-tmpfs "/var/lib/postgresql/data")
    true (conc/add-port 0 5432)))

(defn get-port [name]
  (-> (con/container-ipv4-ports name)
      (get 5432)
      first))

(defn wait-pg-ready!
  "Attempt running a query until postgres is ready."
  [datasource timeout-ms]
  (log/info "Waiting until postgres is ready")
  (let [start (System/nanoTime)
        timeout-ns (* timeout-ms 1000000)]
    (while
     (not
      (try
        (jdbc/execute! datasource ["SHOW work_mem"])
        (catch org.postgresql.util.PSQLException e
          (when (< timeout-ns (- (System/nanoTime) start))
            (throw (ex-info "Failed to connect to postgres"
                            {:datasource datasource
                             :timeout-ms timeout-ms}
                            e)))))))
    (log/info "Postgres ready in" (quot (- (System/nanoTime) start) 1000000) "ms")))

(defn start! [{:keys [image port timeout-ms]}]
  (let [cfg (container-config image (or port 0))
        name (str "tmp-sysrev-pg-" (random-uuid))
        shutdown (shut/add-hook! #(con/kill-container! name))
        _ (con/up! name cfg)
        bound-port (ul/wait-timeout
                    #(get-port name)
                    :timeout-f #(throw (ex-info "Could not find port for container"
                                                {:name name}))
                    :timeout-ms 30000)]
    (wait-pg-ready!
     (jdbc/get-datasource {:dbtype "postgres"
                           :host "localhost"
                           :port bound-port
                           :user "postgres"})
     30000)
    {:name name
     :port bound-port
     :stop! (fn [] @shutdown)}))
