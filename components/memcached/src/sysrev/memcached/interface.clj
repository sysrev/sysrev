(ns sysrev.memcached.interface
  (:require [sysrev.memcached.core :as core]))

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
