(ns sysrev.infra.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [donut.system :as ds]
            [salmon.cloudformation :as cfn]
            [salmon.signal :as sig]
            [sysrev.infra.datapub :as datapub]
            [sysrev.infra.global :as global]
            [sysrev.infra.graphql-gateway :as graphql-gateway]
            [sysrev.infra.regional :as regional]
            [sysrev.infra.sysrev :as sysrev]))

(defonce system (atom nil))

(def capabilities #{"CAPABILITY_AUTO_EXPAND" "CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"})

(defn config-val [k]
  (ds/ref [:common :config k]))

(def global-outputs (ds/ref [:common :global-resources :outputs]))

(def regional-outputs (ds/ref [:common :regional-resources :outputs]))

(defn merge-maps [& maps]
  {::ds/config {:maps maps}
   ::ds/start (fn [{:keys [maps]}]
                (reduce merge {} maps))})

(defn call [f & args]
  {::ds/config {:args args :f f}
   ::ds/start (fn [{{:keys [args f]} ::ds/config}]
                (apply f args))})

(defn system-map []
  {::ds/base {:salmon/pre-validate sig/pre-validate-conf}
   ::ds/defs
   {:common
    {:config (call #(-> % io/resource slurp edn/read-string)
                   "cloudformation-config.edn")
     :global-params
     (merge-maps {:Env (config-val :Env)}
                 (config-val :global))
     :global-resources
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev-Global-Resources"
                 :parameters (ds/ref [:common :global-params])
                 :template global/template})
     :regional-params
     (call (fn [outputs]
             (-> outputs
                 (select-keys [:CloudFrontOAI :CodeBucket :CredentialsGroupName
                               :DatapubHostedZoneId :DatapubZoneApex
                               :SysrevHostedZoneId :SysrevZoneApex])
                 (assoc :NumberOfAZs 6)))
           global-outputs)
     :regional-resources
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev-Regional-Resources"
                 :parameters (ds/ref [:common :regional-params])
                 :template regional/template})}
    :datapub
    {:params
     (call (fn [env config global regional]
             (-> config
                 (merge
                  (select-keys global [:CloudFrontOAI :DatapubBucket :DatapubHostedZoneId
                                       :DatapubZoneApex])
                  (select-keys regional [:CredentialKeysId :CredentialsKeyUsePolicyArn
                                         :LoadBalancerCanonicalHostedZoneId :LoadBalancerDNSName
                                         :LoadBalancerHTTPSListenerArn :LoadBalancerSecurityGroupId
                                         :LogsKeyArn :RDSSubnetGroupName
                                         :VpcId :VpcSubnetIds]))
                 (assoc :AMI (System/getenv "DATAPUB_AMI")
                        :Env env
                        :FilesDomainName (str "files." (:DatapubZoneApex global))
                        :SlackToken (System/getenv "DATAPUB_SLACK_TOKEN"))))
           (ds/ref [:common :config :Env])
           (config-val :datapub)
           global-outputs
           regional-outputs)
     :stack
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Datapub"
                 :parameters (ds/ref [:datapub :params])
                 :template datapub/template})}
    :graphql-gateway
    {:params
     (call #(-> (select-keys % [:CodeBucket :SysrevHostedZoneId :SysrevZoneApex])
                (assoc :ApolloKey (System/getenv "APOLLO_KEY")
                       :LambdaKey (System/getenv "LAMBDA_KEY")))
           global-outputs)
     :stack
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev-GraphQL-Gateway"
                 :parameters (ds/ref [:graphql-gateway :params])
                 :template graphql-gateway/template})}
    :sysrev
    {:params
     (call (fn [config global regional]
             (merge
              config
              (select-keys global [:CredentialsGroupName])
              (select-keys regional [:CredentialsKeyId :RDSSubnetGroupName :VpcId])))
           (config-val :sysrev)
           global-outputs
           regional-outputs)
     :stack
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev"
                 :parameters (ds/ref [:sysrev :params])
                 :template sysrev/template})}}
   ::ds/signals
   {:salmon/pre-validate {:order :reverse-topsort}}})

(defn deploy! [{:keys [groups]}]
  (try
    (-> (system-map)
        (update ::ds/defs select-keys (into #{:common} groups))
        sig/pre-validate!
        sig/start!
        (->> (reset! system)))
    (catch clojure.lang.ExceptionInfo e
      (log/error e (ex-message e) (ex-data e)))))

(comment
  (do
    (deploy! {:groups [:sysrev]})
    nil)
  (->> @system ::ds/instances :common :global-resources :outputs)
  (->> @system ::ds/instances :common :regional-resources :outputs)
  (->> @system ::ds/instances :sysrev :params)
  (->> @system ::ds/instances :sysrev :stack :resources))
