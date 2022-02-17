(ns sysrev.cloudformation-templates.graphql-gateway
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

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
  {:ApolloKeySecret
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:KmsKeyId (import-regional "CredentialsKeyId")
     :SecretString (ref :ApolloKey)}}

   :ApiCertificate
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
       :APOLLO_KEY (sub "{{resolve:secretsmanager:${ApolloKeySecret}::}}")}}
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

(comment
  (write-template "components/cloudformation-templates/out/graphql-gateway.template"
                  template))

