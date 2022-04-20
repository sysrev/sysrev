(ns sysrev.cloudformation-templates.graphql-gateway
  (:refer-clojure :exclude [ref])
  (:require [donut.system :as ds]
            [io.staticweb.cloudformation-templating :refer :all :exclude [template]]
            [salmon.cloudformation.interface :as cfn]
            [salmon.signal.interface :as sig]))

(defn import-regional [export-name]
  (import-value (str "Sysrev-Regional-Resources-" (full-name export-name))))

(deftemplate template
  :Description
  "This template creates a federated GraphQL gateway."

  :AWSTemplateFormatVersion "2010-09-09"
  :Transform "AWS::Serverless-2016-10-31"

  :Parameters
  {:ApolloKey {:Default ""
               :NoEcho true
               :Type "String"}
   :LambdaKey {:Type "String"}}

  :Resources
  {:ApiCertificate
   {:Type "AWS::CertificateManager::Certificate"
    :Properties
    {:DomainName (join "." ["api" (import-regional :SysrevZoneApex)])
     :DomainValidationOptions
     [{:DomainName (join "." ["api" (import-regional :SysrevZoneApex)])
       :HostedZoneId (import-regional :SysrevHostedZoneId)}]
     :ValidationMethod "DNS"}}

   :HttpApi
   {:Type "AWS::Serverless::HttpApi"
    :Properties
    {:Domain
     {:CertificateArn (ref :ApiCertificate)
      :DomainName (join "." ["api" (import-regional :SysrevZoneApex)])
      :IPV6 true
      :Route53 {:HostedZoneId (import-regional :SysrevHostedZoneId)}}}}

   :GatewayHandlerFunction
   {:Type "AWS::Serverless::Function"
    :Properties
    {:AutoPublishAlias "GraphQLGateway"
     :CodeUri
     {:Bucket (import-regional :CodeBucket)
      :Key (ref :LambdaKey)}
     :Environment
     {:Variables
      {:APOLLO_GRAPH_REF "sysrev@current"
       :APOLLO_KEY (ref :ApolloKey)}}
     :Handler "lambda.handler"
     :MemorySize 128
     :PackageType "Zip"
     :Runtime "nodejs14.x"
     :Events
     {:AnyRequest
      {:Type "HttpApi"
       :Properties
       {:ApiId (ref :HttpApi)
        :Method "ANY"
        :Path "/graphql"}}}

     ; The federated server can have long cold start times, so allow a
     ; timeout of at least 10 seconds.
     :Timeout 10}}})

(defn system []
  {::ds/base {:salmon/pre-validate sig/pre-validate-conf}
   ::ds/defs
   {:services {:stack
               (cfn/stack {:lint? true
                           :name "stack"
                           :template template})}}
   ::ds/signals
   {:salmon/pre-validate {:order :reverse-topsort}
    :validate {:order :reverse-topsort}}})

(comment
  (do (sig/pre-validate! (system))
      (write-template "components/cloudformation-templates/out/graphql-gateway.template"
                      template)))

