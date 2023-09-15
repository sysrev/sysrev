(ns sysrev.memcached.core
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojurewerkz.spyglass.client :as spy]
            [com.stuartsierra.component :as component]
            [sysrev.util-lite.interface :as ul])
  (:import (java.util.concurrent CancellationException)))

(defn flush! [{:keys [cache client] :as component}]
  @(spy/flush client)
  component)

(defrecord TempClient [cache client server]
  component/Lifecycle
  (start [this]
    (if client
      this
      (->> server :bound-port
           (str "localhost:")
           spy/bin-connection
           (assoc this :client))))
  (stop [this]
    (if-not client
      this
      (assoc this :client nil :server nil))))

(defn temp-client []
  (map->TempClient {}))

(defn available? [port]
  {:pre [(pos-int? port)]}
  (let [cf (spy/bin-connection-factory {:failure-mode "cancel"})
        conn (spy/bin-connection (str "localhost:" port) cf)]
    (try
      (spy/gets conn "k")
      true
      (catch CancellationException _
        false)
      (finally
        (spy/shutdown conn)))))

(defn wait-until-available [port]
  (ul/wait-timeout
   #(available? port)
   :timeout-f #(throw (ex-info "Could not connect to memcached"
                               {:port port}))
   :timeout-ms 30000))

(defn run-server [port]
  (ul/retry
   {:interval-ms 10
    :n (if (zero? port) 0 3)}
   (let [bound-port (if (zero? port) (+ 20000 (rand-int 10000)) port)
         server (p/process
                 {}
                 "memcached" "-l" (str "127.0.0.1:" bound-port))]
     (wait-until-available bound-port)
     {:bound-port bound-port
      :server server})))

(defrecord TempServer [bound-port port server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (merge this (run-server port))))
  (stop [this]
    (if-not server
      this
      (assoc this :server nil))))

(defn temp-server [& {:keys [port]}]
  (map->TempServer {:port (or port 0)}))

(defn cache*
  [{:keys [client]} ^String key ^Long ttl-sec f]
  (if-let [v (spy/get client key)]
    (try
      (edn/read-string v)
      (catch Exception e
        (throw (ex-info "Failed to deserialize cached value"
                        {:key key :ttl-sec ttl-sec :value v}
                        e))))
    (let [r (f)]
      (spy/set client key ttl-sec (pr-str r))
      r)))

(defmacro cache [component ^String key ^Long ttl-sec & body]
  `(let [component# ~component
         key# ~key]
     (cache* component# key# ~ttl-sec
             (fn [] ~@body))))
