(ns sysrev.util-lite.interface
  "Utility functions with no dependencies"
  (:require
   [sysrev.util-lite.core :as core]))

(defn full-name
  "Returns fully-qualified symbol names, or plain symbol names for un-namespaced symbols.
   Returns nil and strings unchanged."
  ^String [x]
  (core/full-name x))

(defmacro retry
  "Retry body up to n times, doubling interval-ms each time and adding jitter."
  [{:keys [interval-ms n] :as opts} & body]
  `(core/retry ~opts ~@body))
