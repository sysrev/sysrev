(ns datapub.aws-client
  (:require [aws-api-failjure :as aaf]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [com.stuartsierra.component :as component]))

(defrecord AwsClient [after-start client client-opts]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [creds (select-keys client-opts [:access-key-id :secret-access-key])
            client-opts (if (some seq (vals creds))
                          (assoc client-opts :credentials-provider
                                 (credentials/basic-credentials-provider creds))
                          client-opts)
            client-opts (select-keys client-opts [:api :credentials-provider :endpoint-override])
            this (assoc this :client (aws/client client-opts))]
        (if after-start
          (after-start this)
          this))))
  (stop [this]
    (when client
      (aws/stop client))
    (assoc this :client nil)))

(defn aws-client [{:as m :keys [after-start client-opts]}]
  (map->AwsClient m))

(defn invoke! [client op-map]
  (aaf/throwing-invoke (:client client client) op-map))
