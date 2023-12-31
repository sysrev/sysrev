(ns sysrev.localstack.core
  (:require [cognitect.aws.client.api :as aws]
            [com.stuartsierra.component :as component]
            [sysrev.aws-client.interface :as aws-client]
            [sysrev.contajners.interface :as con]
            [sysrev.contajners.interface.config :as conc]
            [sysrev.util-lite.interface :as ul]))

(defn localstack-config []
  (cond-> {:Env ["SERVICES=s3"]
           :HostConfig {:AutoRemove true}
           :Image "localstack/localstack:1.3.1"}
    (conc/linux?) (conc/add-tmpfs "/var/lib/localstack")
    true (conc/add-port 0 4566)))

(defn list-buckets [client-opts localstack]
  (-> (aws-client/aws-client
       {:client-opts (assoc client-opts :api :s3)})
      (assoc :localstack localstack)
      component/start
      :client
      (aws/invoke {:op :ListBuckets :request {}})))

(defn s3-available? [client-opts localstack]
  (-> (list-buckets client-opts localstack)
      :cognitect.anomalies/category
      not))

(defn localstack-after-start [client-opts {:keys [ports] :as localstack}]
  (let [port (-> ports first val)]
    (ul/wait-timeout
     #(s3-available? client-opts localstack)
     :timeout-f #(let [response (list-buckets client-opts localstack)]
                   (when (:cognitect.anomalies/category response)
                     (throw (ex-info "Could not connect to localstack"
                                     {:port port
                                      :response response}))))
     :timeout-ms 30000))
  localstack)

(defn localstack [aws-client-opts]
  (con/temp-container "tmp-localstack-" (localstack-config)
                      :after-start-f (partial localstack-after-start aws-client-opts)))
