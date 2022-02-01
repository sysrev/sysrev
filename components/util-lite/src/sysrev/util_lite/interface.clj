(ns sysrev.util-lite.interface
  "Utility functions with no dependencies"
  (:require
   [sysrev.util-lite.core :as core]))

(defmacro retry
  "Retry body up to n times, doubling interval-ms each time and adding jitter."
  [{:keys [interval-ms n] :as opts} & body]
  `(core/retry ~opts ~@body))
