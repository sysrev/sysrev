(ns sysrev.aws-client.interface
  (:require [sysrev.aws-client.core :as core]))

(defn aws-client
  "Returns an AWS Client record implementing
   `com.stuartsierra.component/Lifecycle`.
   
   `client-opts` are passed to `com.cognitect.aws.client.api/client`"
  [& {:keys [after-start client-opts]}]
  (core/aws-client
   {:after-start after-start
    :client-opts client-opts}))

(defn invoke!
  "Returns the result of `com.cognitect.aws.client.api/invoke`
   on the `client`. `client` may be the result of `aws-client` or an
   aws-api client.
   
   Throws if there is an anomaly."
  [client op-map]
  (core/invoke! client op-map))
