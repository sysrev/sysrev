(ns datapub.secrets-manager
  (:require [cheshire.core :as json]
            [sysrev.aws-client.interface :as aws-client]))

(defn client []
  (aws-client/aws-client {:client-opts {:api :secretsmanager}}))

(defn get-config-secret [secrets-manager {:secrets-manager/keys [arn key]}]
  (-> (aws-client/invoke!
       secrets-manager
       {:op :GetSecretValue
        :request {:SecretId arn}})
      :SecretString
      (json/parse-string keyword)
      (get key)))
