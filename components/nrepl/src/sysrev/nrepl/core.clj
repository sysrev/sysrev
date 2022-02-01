(ns sysrev.nrepl.core
  (:require
   [cider.nrepl.middleware]
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [nrepl.server]
   [refactor-nrepl.middleware])
  (:import
   (java.net ServerSocket)))

(def nrepl-handler
  (apply nrepl.server/default-handler
         (conj cider.nrepl.middleware/cider-middleware
               #'refactor-nrepl.middleware/wrap-refactor)))

(defrecord NRepl [bound-port config server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [server (nrepl.server/start-server
                    :bind "localhost"
                    :handler nrepl-handler
                    :port (or (get-in config [:nrepl :port]) 0))
            bound-port (.getLocalPort ^ServerSocket (:server-socket server))]
        (log/info "Started nREPL server on port" bound-port)
        (assoc this :bound-port bound-port :server server))))
  (stop [this]
    (if-not server
      this
      (do
        (nrepl.server/stop-server server)
        (log/info "Stopped nREPL server")
        (assoc this :bound-port nil :server nil)))))

