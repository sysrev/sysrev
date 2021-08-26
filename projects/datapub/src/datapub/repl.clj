(ns datapub.repl
  (:require [com.stuartsierra.component :as component]
            [datapub.main :as main]
            [nrepl.server :as ns]
            [taoensso.timbre :as t]))

(defn nrepl-handler []
  (require 'cider.nrepl)
  (ns-resolve 'cider.nrepl 'cider-nrepl-handler))

(defrecord NREPLServer [port server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [server (ns/start-server :bind "localhost" :port port
                     :handler (nrepl-handler))]
        (t/info "Started nREPL server on port" (:port server))
        (assoc this :server server))))
  (stop [this]
    (when server
      (ns/stop-server server)
      (t/info "Stopped nREPL server"))
    (assoc this :server nil)))

(defn nrepl-server [config]
  (map->NREPLServer config))

(defonce main-nrepl
  (atom nil))

(defn start-nrepl! [& _]
  (swap! main-nrepl
    (fn [nrepl]
      (or nrepl
        (component/start
         (nrepl-server (:nrepl (:port 0))))))))

(defn run-with-fixtures! [& _]
  (->> (start-nrepl!) :server :port (spit ".nrepl-port"))
  (main/reload-with-fixtures!)
  (clojure.main/repl))
