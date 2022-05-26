(ns sysrev.aws-client.core
  (:require [aws-api-failjure :as aaf]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [com.stuartsierra.component :as component]))

(defn basic-credentials-provider [{:keys [access-key-id secret-access-key session-token]}]
  (reify credentials/CredentialsProvider
    (fetch [_]
      {:aws/access-key-id access-key-id
       :aws/secret-access-key secret-access-key
       :aws/session-token session-token})))

(defn aws-api-client [opts]
  (let [creds (select-keys opts [:access-key-id :secret-access-key :session-token])]
    (-> (if (some seq (vals creds))
          (assoc opts :credentials-provider
                 (basic-credentials-provider creds))
          opts)
        aws/client)))

(defrecord AwsClient [after-start client client-opts localstack]
  component/Lifecycle
  (start [this]
    (if client
      this
      (if (:disabled? client-opts)
        this
        (let [localstack-port (some-> localstack :ports first val first)
              client-opts (cond-> (select-keys client-opts [:api :credentials-provider :endpoint-override :region])
                            localstack-port (assoc-in [:endpoint-override :port] localstack-port))
              this (assoc this :client (aws-api-client client-opts))]
          (if after-start
            (after-start this)
            this)))))
  (stop [this]
    (when client
      (aws/stop client))
    (assoc this :client nil)))

(defn aws-client [& {:keys [after-start client-opts]}]
  (map->AwsClient
   {:after-start after-start
    :client-opts client-opts}))

(defn invoke! [client op-map]
  (aaf/throwing-invoke (:client client client) op-map))
