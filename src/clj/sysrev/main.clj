(ns sysrev.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sysrev.config :refer [env]]
            [sysrev.web.core :as web]))

(defonce system (atom nil))

(defn system-map []
  (component/system-map
   :sente (web/sente)
   :web-server (component/using (web/web-server
                                 :handler-f web/sysrev-handler
                                 :port (-> env :server :port))
                                [:sente])))

(defn start-system! []
  (log/info "Starting system")
  (reset! system (component/start (system-map)))
  (log/info "System started"))

(defn stop-system! []
  (log/info "Stopping system")
  (swap! system component/stop)
  (log/info "System stopped"))
