(ns sysrev.memcached.interface
  (:require [sysrev.memcached.core :as core]))

(defmacro cache
  "Caches the body in memcached. The body will be serialized with
   pr-str and must be deserializable by clojure.edn/read-string.
   
   Executions for the same key that occur in a short time period will
   be combined into a single execution."
  [component ^String key ^Long ttl-sec & body]
  `(core/cache ~component ~key ~ttl-sec ~@body))

(defn flush!
  "Empty the memcached server and the client's internal cache."
  [component]
  (core/flush! component))

(defn temp-client
  "Returns a record implementing com.stuartsierra.component/Lifecycle
   that starts and stop a client for a `temp-server`.
   
   The temp-server should be assoc'd as :server before calling
   component/start."
  []
  (core/temp-client))

(defn temp-server
  "Returns a record implementing com.stuartsierra.component/Lifecycle
   that starts and stop a temporary memcached container."
  []
  (core/temp-server))