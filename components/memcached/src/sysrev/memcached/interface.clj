(ns sysrev.memcached.interface
  (:require [sysrev.memcached.core :as core]))

(defmacro cache
  "Caches the body in memcached. The body will be serialized with
   pr-str and must be deserializable by clojure.edn/read-string."
  [component ^String key ^Long ttl-sec & body]
  `(core/cache ~component ~key ~ttl-sec ~@body))

(defn cache-get
  "Gets a cached value."
  [component ^String key & [not-found]]
  (core/cache-get component key not-found))

(defn cache-set
  "Sets a key in the cache."
  [component ^String key ^Long ttl-sec v]
  (core/cache-set component key ttl-sec v))

(defn flush!
  "Empty the memcached server."
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
   that starts and stop a temporary memcached server

   Example:
   (temp-server
     {:mem-mb 1024
      :port 0})"
  [& [opts]]
  (core/temp-server opts))
