(ns sysrev.postgres.embedded
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [sysrev.contajners.interface :as con]
            [sysrev.shutdown.interface :as shut]))

(defn container-config [image port]
  {:keys [(seq image) port]}
  {:Env ["POSTGRES_HOST_AUTH_METHOD=trust"]
   :ExposedPorts {"5432/tcp" {}}
   :HostConfig {:AutoRemove true
                :PortBindings {"5432/tcp"
                               [{:HostPort (str port)}]}}
   :Image image})

(defn get-port [name]
  (-> (con/container-ports name)
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
        name (str "sysrev-pg-" (random-uuid))
        shutdown (shut/add-hook! #(con/stop-container! name))
        _ (con/up! name cfg)
        bound-port (get-port name)]
    (wait-pg-ready!
     (jdbc/get-datasource {:dbtype "postgres"
                           :host "localhost"
                           :port bound-port
                           :user "postgres"})
     30000)
    {:name name
     :port bound-port
     :stop! (fn [] @shutdown)}))

(defn stop! [{:keys [stop!]}]
  (stop!))
