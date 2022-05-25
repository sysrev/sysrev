(ns sysrev.secrets-manager.interface
  (:require [aws-api-failjure :as aaf]
            [cognitect.aws.client.api :as aws]
            [com.rpl.specter :as sp]
            [sysrev.json.interface :as json]))

(defn- aws-client []
  (aws/client {:api :secretsmanager}))

(defn get-secret-value [client arn]
  (-> client
      (aaf/throwing-invoke {:op :GetSecretValue
                            :request {:SecretId arn}})
      :SecretString
      (json/read-str :key-fn keyword)))

(defn transform-secrets [m]
  (let [client (aws-client)
        get-val (memoize (partial get-secret-value client))]
    (sp/transform
     (sp/walker :secrets-manager/arn)
     (fn [{:secrets-manager/keys [arn key]}]
       (let [v (get (get-val arn) key ::not-found)]
         (if (= ::not-found v)
           (throw (ex-info "Key not found in secret value" {:arn arn :key key}))
           v)))
     m)))
