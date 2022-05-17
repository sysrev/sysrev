(ns sysrev.cloudformation-templates.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [donut.system :as ds]
            [salmon.cloudformation.interface :as cfn]
            [salmon.signal.interface :as sig]
            [sysrev.cloudformation-templates.sysrev-global-resources :as global]
            [sysrev.cloudformation-templates.sysrev-regional-resources :as regional]))

(defonce system (atom nil))

(defn global-output [k]
  (ds/ref [:stacks :global-resources :outputs k]))

(defn config-val [k]
  (ds/ref [:stacks :config k]))

(defn system-map []
  {::ds/base {:salmon/pre-validate sig/pre-validate-conf}
   ::ds/defs
   {:stacks
    {:config (-> "cloudformation-config.edn" io/resource slurp edn/read-string)
     :global-resources
     (cfn/stack {:capabilities #{"CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"}
                 :lint? true
                 :name "Sysrev-Global-Resources"
                 :parameters {:DatapubZoneApex (config-val :datapub-zone-apex)
                              :Env (config-val :env)
                              :InsilicaZoneApex (config-val :insilica-zone-apex)
                              :SysrevZoneApex (config-val :sysrev-zone-apex)}
                 :template global/template})
     :regional-resources
     (cfn/stack {:capabilities #{"CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"}
                 :lint? true
                 :name "Sysrev-Regional-Resources"
                 :parameters {:CloudFrontOAI (global-output :CloudFrontOAI)
                              :CodeBucket (global-output :CodeBucket)
                              :DatapubHostedZoneId (global-output :DatapubHostedZoneId)
                              :DatapubZoneApex (global-output :DatapubZoneApex)
                              :NumberOfAZs 6
                              :SysrevHostedZoneId (global-output :SysrevHostedZoneId)
                              :SysrevZoneApex (global-output :SysrevZoneApex)}
                 :template regional/template})}}
   ::ds/signals
   {:salmon/pre-validate {:order :reverse-topsort}}})

(defn deploy! [& _]
  (let [sys (system-map)]
    (try
      (sig/pre-validate! sys)
      (reset! system (sig/start! sys))
      (catch clojure.lang.ExceptionInfo e
        (log/error (ex-message e) e)))))

(comment
  (deploy!)
  (->> @system ::ds/instances :stacks :global-resources :outputs)
  (->> @system ::ds/instances :stacks :regional-resources :outputs))
