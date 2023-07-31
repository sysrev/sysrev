(ns sysrev.infra.datapub
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(deftemplate template
  :Description
  "This template creates the Datapub file hosting."

  :Parameters
  {:CloudFrontOAI {:Type "String"}
   :CredentialsKeyId {:Type "String"}
   :DatapubBucket {:MaxLength 63
                   :MinLength 3
                   :Type "String"}
   :DatapubHostedZoneId {:Type "String"}
   :FilesDomainName {:Type "String"}
   :LogsKeyArn {:Type "String"}}

  :Resources
  {:LogGroup
   {:Type "AWS::Logs::LogGroup"
    :Properties
    {:KmsKeyId (ref :LogsKeyArn)
     :LogGroupName "Datapub-Servers"
     :RetentionInDays 14}}

   :FileDistributionCertificate
   {:Type "AWS::CertificateManager::Certificate"
    :Properties
    {:DomainName (ref :FilesDomainName)
     :DomainValidationOptions
     [{:DomainName (ref :FilesDomainName)
       :HostedZoneId (ref :DatapubHostedZoneId)}]
     :ValidationMethod "DNS"}}

   :FileDistributionCachePolicy
   {:Type "AWS::CloudFront::CachePolicy"
    :Properties
    {:CachePolicyConfig
     {:DefaultTTL 86400
      :MaxTTL 31536000
      :MinTTL 0
      :Name "Datapub-FileDistributionCachePolicy"
      :ParametersInCacheKeyAndForwardedToOrigin
      {:CookiesConfig
       {:CookieBehavior "none"}
       :EnableAcceptEncodingGzip true
       :HeadersConfig
       {:HeaderBehavior "whitelist"
        :Headers
        ["Access-Control-Request-Headers"
         "Access-Control-Request-Method"
         "Origin"]}
       :QueryStringsConfig
       {:QueryStringBehavior "none"}}}}}

   :FileDistribution
   {:Type "AWS::CloudFront::Distribution"
    :Properties
    {:DistributionConfig
     {:Aliases [(ref :FilesDomainName)]
      :DefaultCacheBehavior
      {:AllowedMethods ["GET" "HEAD" "OPTIONS"]
       :CachedMethods ["GET" "HEAD" "OPTIONS"]
       :CachePolicyId (ref :FileDistributionCachePolicy)
       :Compress true
       :TargetOriginId "DatapubBucketOrigin"
       :ViewerProtocolPolicy "redirect-to-https"}
      :Enabled true
      :HttpVersion "http2"
      :Origins
      [{:DomainName (join "" [(ref :DatapubBucket) ".s3.amazonaws.com"])
        :Id "DatapubBucketOrigin"
        :S3OriginConfig
        {:OriginAccessIdentity (join "" ["origin-access-identity/cloudfront/"
                                         (ref :CloudFrontOAI)])}}]
      :ViewerCertificate
      {:AcmCertificateArn (ref :FileDistributionCertificate)
       :SslSupportMethod "sni-only"}}}}

   :FileDistributionRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (ref :DatapubHostedZoneId)
     :RecordSets
     [{:AliasTarget {:HostedZoneId cloudfront-hosted-zone-id
                     :DNSName (get-att :FileDistribution "DomainName")}
       :Name (ref :FilesDomainName)
       :Type "A"}
      {:AliasTarget {:HostedZoneId cloudfront-hosted-zone-id
                     :DNSName (get-att :FileDistribution "DomainName")}
       :Name (ref :FilesDomainName)
       :Type "AAAA"}]}}

   :SysrevDevKey
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:GenerateSecretString
     {:GenerateStringKey "key"
      :PasswordLength 32
      :SecretStringTemplate "{}"}
     :KmsKeyId (ref :CredentialsKeyId)}}})
