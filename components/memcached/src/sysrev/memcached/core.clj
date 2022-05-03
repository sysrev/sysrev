(ns sysrev.memcached.core
  (:require [clojure.core.cache.wrapped :as cw]
            [clojure.edn :as edn]
            [clojurewerkz.spyglass.client :as spy]
            [com.stuartsierra.component :as component]
            [sysrev.contajners.interface :as con]
            [sysrev.contajners.interface.config :as conc]
            [sysrev.util-lite.interface :as ul])
  (:import (java.util.concurrent CancellationException)))

(defrecord TempClient [cache client server]
  component/Lifecycle
  (start [this]
    (if client
      this
      (->> server :ports first val first
           (str "localhost:")
           spy/bin-connection
           (assoc this
                  :cache (cw/ttl-cache-factory {} :ttl 2000)
                  :client))))
  (stop [this]
    (if-not client
      this
      (assoc this :client nil :server nil))))

(defn temp-client []
  (map->TempClient {}))

(defn container-config []
  (-> {:HostConfig {:AutoRemove true}
       :Image "docker.io/library/memcached:1.6.15"}
      (conc/add-port 0 11211)))

(defn available? [port]
  {:pre [(pos-int? port)]}
  (let [cf (spy/bin-connection-factory {:failure-mode "cancel"})
        conn (spy/bin-connection (str "localhost:" port) cf)]
    (try
      (spy/get-stats conn) ;; For some reason spy/gets fails without this line
      (spy/gets conn "k")
      true
      (catch CancellationException _
        false)
      (finally
        (spy/shutdown conn)))))

(defn after-start [{:keys [ports] :as component}]
  (let [port (-> ports first val first)]
    (ul/wait-timeout
     #(available? port)
     :timeout-f #(throw (ex-info "Could not connect to localstack"
                                 {:name name :port port}))
     :timeout-ms 30000))
  component)

(defn temp-server []
  (con/temp-container "tmp-memcached-" (container-config)
                      :after-start-f after-start))

(defn cache*
  [{:keys [client]} ^String key ^Long ttl-sec f]
  (if-let [v (spy/get client key)]
    (edn/read-string v)
    (let [r (f)]
      (spy/set client key ttl-sec (pr-str r))
      r)))

(defmacro cache [component ^String key ^Long ttl-sec & body]
  `(let [component# ~component
         key# ~key]
     (cw/lookup-or-miss
      (:cache component#)
      key#
      (fn [_#]
        (cache* component# key# ~ttl-sec
                (fn [] ~@body))))))
