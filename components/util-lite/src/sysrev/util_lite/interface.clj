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
  "Retries body up to n times, doubling interval-ms each time
   and adding jitter.
   
   If throw-pred is provided, it will be called on the exception. If
   throw-pred returns true, the exception is re-thrown and the body is
   not retried."
  [opts & body]
  `(core/retry ~opts ~@body))

(defn wait-timeout
  "Retries pred until it returns a truthy value or timeout-ms is reached.
   Calls timeout-f in that case."
  [pred & {:keys [timeout-f timeout-ms] :as opts}]
  (core/wait-timeout pred opts))
