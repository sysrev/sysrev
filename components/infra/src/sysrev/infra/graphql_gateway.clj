(ns sysrev.infra.graphql-gateway
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(deftemplate template
  :Description
  "This template creates a federated GraphQL gateway."

  :AWSTemplateFormatVersion "2010-09-09"
  :Transform "AWS::Serverless-2016-10-31"

  :Parameters
  {:ApolloKey {:Default ""
               :NoEcho true
               :Type "String"}
   :CodeBucket {:Type "String"}
   :LambdaKey {:Type "String"}
   :SysrevHostedZoneId {:Type "String"}
   :SysrevZoneApex {:Type "String"}}

  :Resources
  {:ApiCertificate
   {:Type "AWS::CertificateManager::Certificate"
    :Properties
    {:DomainName (join "." ["api" (ref :SysrevZoneApex)])
     :DomainValidationOptions
     [{:DomainName (join "." ["api" (ref :SysrevZoneApex)])
       :HostedZoneId (ref :SysrevHostedZoneId)}]
     :ValidationMethod "DNS"}}

   :HttpApi
   {:Type "AWS::Serverless::HttpApi"
    :Properties
    {:Domain
     {:CertificateArn (ref :ApiCertificate)
      :DomainName (join "." ["api" (ref :SysrevZoneApex)])
      :IPV6 true
      :Route53 {:HostedZoneId (ref :SysrevHostedZoneId)}}}}

   :GatewayHandlerFunction
   {:Type "AWS::Serverless::Function"
    :Properties
    {:AutoPublishAlias "GraphQLGateway"
     :CodeUri
     {:Bucket (ref :CodeBucket)
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
