(ns sysrev.secrets-manager.interface
  (:require [aws-api-failjure :as aaf]
            [com.rpl.specter :as sp]
            [sysrev.aws-client.interface :as aws-client]
            [sysrev.json.interface :as json]))

(defn- aws-client [opts]
  (aws-client/aws-api-client (assoc opts :api :secretsmanager)))

(defn get-secret-value [client arn]
  (-> client
      (aaf/throwing-invoke {:op :GetSecretValue
                            :request {:SecretId arn}})
      :SecretString
      (json/read-str :key-fn keyword)))

(defn transform-secrets [client-opts m]
  (let [client (aws-client client-opts)
        get-val (memoize (partial get-secret-value client))]
    (sp/transform
     (sp/walker :secrets-manager/arn)
     (fn [{:secrets-manager/keys [arn key]}]
       (let [v (get (get-val arn) key ::not-found)]
         (if (= ::not-found v)
           (throw (ex-info "Key not found in secret value" {:arn arn :key key}))
           v)))
     m)))
