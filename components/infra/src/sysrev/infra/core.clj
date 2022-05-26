(ns sysrev.infra.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [donut.system :as ds]
            [salmon.cloudformation.interface :as cfn]
            [salmon.signal.interface :as sig]
            [sysrev.infra.datapub :as datapub]
            [sysrev.infra.graphql-gateway :as graphql-gateway]
            [sysrev.infra.global :as global]
            [sysrev.infra.regional :as regional]
            [sysrev.infra.sysrev :as sysrev]))

(defonce system (atom nil))

(def capabilities #{"CAPABILITY_AUTO_EXPAND" "CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"})

(defn config-val [k]
  (ds/ref [:common :config k]))

(defn global-output [k]
  (ds/ref [:common :global-resources :outputs k]))

(defn regional-output [k]
  (ds/ref [:common :regional-resources :outputs k]))

(defn merge-maps [& maps]
  {::ds/config {:maps maps}
   ::ds/start (fn [{:keys [maps]}]
                (reduce merge {} maps))})

(defn system-map []
  {::ds/base {:salmon/pre-validate sig/pre-validate-conf}
   ::ds/defs
   {:common
    {:config (-> "cloudformation-config.edn" io/resource slurp edn/read-string)
     :global-resources
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev-Global-Resources"
                 :parameters {:DatapubZoneApex (config-val :datapub-zone-apex)
                              :Env (config-val :env)
                              :InsilicaZoneApex (config-val :insilica-zone-apex)
                              :SysrevZoneApex (config-val :sysrev-zone-apex)}
                 :template global/template})
     :regional-resources
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev-Regional-Resources"
                 :parameters {:CloudFrontOAI (global-output :CloudFrontOAI)
                              :CodeBucket (global-output :CodeBucket)
                              :DatapubHostedZoneId (global-output :DatapubHostedZoneId)
                              :DatapubZoneApex (global-output :DatapubZoneApex)
                              :NumberOfAZs 6
                              :SysrevHostedZoneId (global-output :SysrevHostedZoneId)
                              :SysrevZoneApex (global-output :SysrevZoneApex)}
                 :template regional/template})}
    :datapub
    {:files-domain-name
     {::ds/config {:apex (global-output :DatapubZoneApex)}
      ::ds/start (fn [config]
                   (str "files." (:apex config)))}
     :params
     (merge-maps
      (ds/ref [:common :config :datapub])
      {:AMI (System/getenv "DATAPUB_AMI")
       :CloudFrontOAI (global-output :CloudFrontOAI)
       :CredentialsKeyId (regional-output :CredentialsKeyId)
       :CredentialsKeyUsePolicyArn (regional-output :CredentialsKeyUsePolicyArn)
       :DatapubBucket (global-output :DatapubBucket)
       :DatapubFilesDomainName (ds/ref [:datapub :files-domain-name])
       :DatapubHostedZoneId (global-output :DatapubHostedZoneId)
       :DatapubZoneApex (global-output :DatapubZoneApex)
       :Env (config-val :env)
       :LoadBalancerCanonicalHostedZoneId (regional-output :LoadBalancerCanonicalHostedZoneId)
       :LoadBalancerDNSName (regional-output :LoadBalancerDNSName)
       :LoadBalancerHTTPSListenerArn (regional-output :LoadBalancerHTTPSListenerArn)
       :LoadBalancerSecurityGroupId (regional-output :LoadBalancerSecurityGroupId)
       :LogsKeyArn (regional-output :LogsKeyArn)
       :RDSSubnetGroupName (regional-output :RDSSubnetGroupName)
       :SlackToken (System/getenv "DATAPUB_SLACK_TOKEN")
       :VpcId (regional-output :VpcId)
       :VpcSubnetIds (regional-output :VpcSubnetIds)})
     :stack
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Datapub"
                 :parameters (ds/ref [:datapub :params])
                 :template datapub/template})}
    :graphql-gateway
    {:stack
     (cfn/stack {:capabilities capabilities
                 :lint? true
                 :name "Sysrev-GraphQL-Gateway"
                 :parameters {:ApolloKey (System/getenv "APOLLO_KEY")
                              :CodeBucket (global-output :CodeBucket)
                              :LambdaKey (System/getenv "LAMBDA_KEY")
                              :SysrevHostedZoneId (global-output :SysrevHostedZoneId)
                              :SysrevZoneApex (global-output :SysrevZoneApex)}
                 :template graphql-gateway/template})}
    :sysrev
    {:params
     (merge-maps
      (ds/ref [:common :config :sysrev])
      {:CredentialsKeyId (regional-output :CredentialsKeyId)
       :RDSSubnetGroupName (regional-output :RDSSubnetGroupName)
       :VpcId (regional-output :VpcId)})
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
