(ns sysrev.localstack.interface
  (:require [sysrev.localstack.core :as core]))

(defn localstack
  "Returns a record implementing com.stuartsierra.component/Lifecycle
   that starts and stop a temporary localstack container."
  [aws-client-opts]
  (core/localstack aws-client-opts))
