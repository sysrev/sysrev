(ns sysrev.infra.datapub
  (:refer-clojure :exclude [ref])
  (:require [clojure.java.io :as io]
            [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(deftemplate template
  :Description
  "This template creates the Datapub AutoScalingGroup."

  :Parameters
  {:AMI {:Type "AWS::EC2::Image::Id"}
   :AutoScalingMaxSize {:AllowedPattern "[1-9][0-9]*"
                        :Type "String"}
   :AutoScalingMinSize {:AllowedPattern "[1-9][0-9]*"
                        :Type "String"}
   :CloudFrontOAI {:Type "String"}
   :CredentialsKeyId {:Type "String"}
   :CredentialsKeyUsePolicyArn {:Type "String"}
   :DatapubBucket {:MaxLength 63
                   :MinLength 3
                   :Type "String"}
   :DatapubHostedZoneId {:Type "String"}
   :DatapubZoneApex {:Type "String"}
   :Env {:AllowedPattern "(dev)|(prod)|(staging)"
         :Type "String"}
   :FilesDomainName {:Type "String"}
   :InstanceType {:Type "String"}
   :LoadBalancerCanonicalHostedZoneId {:Type "String"}
   :LoadBalancerDNSName {:Type "String"}
   :LoadBalancerHTTPSListenerArn {:Type "String"}
   :LoadBalancerSecurityGroupId {:Type "String"}
   :LogsKeyArn {:Type "String"}
   :KeyName {:Default ""
             :Type "String"}
   :RDSAllocatedStorage {:AllowedPattern "[1-9][0-9]+"
                         :Description "Minimum allocated storage in GB. Must be at least 20."
                         :Type "String"}
   :RDSInstanceClass {:Type "String"}
   :RDSIops {:Default 1000
             :Description "Provisioned Iops for the storage. Only used when RDSStorageType is io1."
             :MinValue 1000
             :Type "Number"}
   :RDSStorageType {:AllowedPattern "(gp2)|(io1)"
                    :Type "String"}
   :RDSSubnetGroupName {:Type "String"}
   :SlackToken {:MinLength 10 ; Just make sure we actually pass a value in
                :NoEcho true
                :Type "String"}
   :VpcId {:Type "String"}
   :VpcSubnetIds {:Type "String"}}

  :Conditions
  {:HasKeyName
   (fn-not (equals "" (ref :KeyName)))
   :RDSIo1Storage
   (equals "io1" (ref :RDSStorageType))}

  :Resources
  {:LogGroup
   {:Type "AWS::Logs::LogGroup"
    :Properties
    {:KmsKeyId (ref :LogsKeyArn)
     :LogGroupName "Datapub-Servers"
     :RetentionInDays 14}}

   :ErrorFunctionExecutionRole
   {:Type "AWS::IAM::Role"
    :Properties
    {:AssumeRolePolicyDocument
     {:Version "2012-10-17"
      :Statement
      {:Action "sts:AssumeRole"
       :Effect "Allow"
       :Principal {:Service ["edgelambda.amazonaws.com" "lambda.amazonaws.com"]}}}
     :ManagedPolicyArns
     ["arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"]}}

   :ErrorFunction
   {:Type "AWS::Lambda::Function"
    :Properties
    {:Description "Sends datapub errors to Slack."
     :Environment
     {:Variables
      {"ENV" (ref :Env)
       "SLACK_CHANNEL" "C02MZERSZ36"
       "SLACK_TOKEN" (ref :SlackToken)}}
     :Handler "index.handler"
     :MemorySize 128
     :Role (arn :ErrorFunctionExecutionRole)
     :Runtime "nodejs12.x"
     :Code
     {:ZipFile
      (slurp (io/resource "sysrev/infra/lambda/log-to-slack.js"))}}}

   :ErrorFunctionPermission
   {:Type "AWS::Lambda::Permission"
    :Properties
    {:Action "lambda:InvokeFunction"
     :FunctionName (ref :ErrorFunction)
     :Principal (sub "logs.${AWS::Region}.amazonaws.com")
     :SourceArn (arn :LogGroup)}}

   :ErrorFunctionFilter
   {:Type "AWS::Logs::SubscriptionFilter"
    :Properties
    {:DestinationArn (arn :ErrorFunction)
     :FilterPattern "?ERROR ?error"
     :LogGroupName (ref :LogGroup)}}

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

   :RDSMasterCredentials
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:GenerateSecretString
     {:ExcludeCharacters "\"@/\\"
      :GenerateStringKey "password"
      :PasswordLength 32
      :SecretStringTemplate "{\"username\": \"postgres\"}"}
     :KmsKeyId (ref :CredentialsKeyId)}}

   :SysrevDevKey
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:GenerateSecretString
     {:GenerateStringKey "key"
      :PasswordLength 32
      :SecretStringTemplate "{}"}
     :KmsKeyId (ref :CredentialsKeyId)}}

   :DatapubSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Datapub Servers"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 8888
       :ToPort 8888
       :SourceSecurityGroupId (ref :LoadBalancerSecurityGroupId)}]
     :VpcId (ref :VpcId)}}

   :RDSSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Datapub RDS Instances"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 5432
       :ToPort 5432
       :SourceSecurityGroupId (ref :DatapubSecurityGroup)}]
     :VpcId (ref :VpcId)}}

   :RDSInstance
   {:Type "AWS::RDS::DBInstance"
    :DeletionPolicy "Snapshot"
    :UpdateReplacePolicy "Snapshot"
    :Properties
    {:AllocatedStorage (ref :RDSAllocatedStorage)
     :AllowMajorVersionUpgrade true
     :AutoMinorVersionUpgrade true
     :BackupRetentionPeriod 7
     :CopyTagsToSnapshot true
     :DBInstanceClass (ref :RDSInstanceClass)
     :DBInstanceIdentifier "datapubio"
     :DBName "datapub"
     :DBSubnetGroupName (ref :RDSSubnetGroupName)
     :DeletionProtection true
     :EnablePerformanceInsights true
     :Engine "postgres"
     :EngineVersion "13.3"
     :Iops (fn-if :RDSIo1Storage (ref :RDSIops) no-value)
     :MasterUsername (sub "{{resolve:secretsmanager:${RDSMasterCredentials}::username}}")
     :MasterUserPassword (sub "{{resolve:secretsmanager:${RDSMasterCredentials}::password}}")
     :MaxAllocatedStorage 1000
     :MultiAZ false
     :PreferredBackupWindow "08:48-09:18"
     :PreferredMaintenanceWindow "Sun:05:57-Sun:06:27"
     :PubliclyAccessible false
     :StorageEncrypted true
     :StorageType (ref :RDSStorageType)
     :Tags (tags :grant "1R43DA052916-01")
     :VPCSecurityGroups [(ref :RDSSecurityGroup)]}}

   :RecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (ref :DatapubHostedZoneId)
     :RecordSets
     [{:AliasTarget
       {:HostedZoneId (ref :LoadBalancerCanonicalHostedZoneId)
        :DNSName (ref :LoadBalancerDNSName)}
       :Name (join "." ["www" (ref :DatapubZoneApex)])
       :Type "A"}
      {:AliasTarget
       {:HostedZoneId (ref :LoadBalancerCanonicalHostedZoneId)
        :DNSName (ref :LoadBalancerDNSName)}
       :Name (join "." ["www" (ref :DatapubZoneApex)])
       :Type "AAAA"}]}}

   :LoadBalancerTargetGroup
   {:Type "AWS::ElasticLoadBalancingV2::TargetGroup"
    :Properties
    {:HealthCheckPath "/health"
     :HealthCheckProtocol "HTTP"
     :Matcher {:HttpCode "200-299"}
     :Port 8888
     :Protocol "HTTP"
     :TargetType "instance"
     :VpcId (ref :VpcId)}}

   :LoadBalancerListenerRule
   {:Type "AWS::ElasticLoadBalancingV2::ListenerRule"
    :Properties
    {:Actions
     [{:TargetGroupArn (ref :LoadBalancerTargetGroup)
       :Type "forward"}]
     :Conditions
     [{:Field "host-header"
       :HostHeaderConfig {:Values [(join "." ["www" (ref :DatapubZoneApex)])]}}]
     :ListenerArn (ref :LoadBalancerHTTPSListenerArn)
     :Priority 200}}

   :DatapubBucketFullAccessPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action "*"
        :Effect "Allow"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :DatapubBucket)])}
       {:Action "*"
        :Effect "Allow"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :DatapubBucket) "/*"])}]}}}

   :InstancePolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["elasticloadbalancing:DescribeTargetHealth"]
        :Effect "Allow"
        :Resource "*"}
       {:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :RDSMasterCredentials)}
       {:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :SysrevDevKey)}]}}}

   :InstanceRole
   {:Type "AWS::IAM::Role"
    :Properties
    {:AssumeRolePolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Principal {:Service ["ec2.amazonaws.com"]}
        :Action ["sts:AssumeRole"]}]}
     :Path "/Sysrev/Datapub/"
     :ManagedPolicyArns
     ["arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
      "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
      (ref :DatapubBucketFullAccessPolicy)
      (ref :InstancePolicy)
      (ref :CredentialsKeyUsePolicyArn)]}}

   :InstanceProfile
   {:Type "AWS::IAM::InstanceProfile"
    :Properties
    {:Path "/Sysrev/Datapub/"
     :Roles [(ref :InstanceRole)]}}

   :LaunchTemplate
   {:Type "AWS::EC2::LaunchTemplate"
    :Properties
    {:LaunchTemplateData
     {:BlockDeviceMappings
      [{:DeviceName "/dev/xvda"
        :Ebs
        {:DeleteOnTermination true
         :Encrypted true
         :VolumeType "gp3"}}]
      :IamInstanceProfile {:Arn (get-att :InstanceProfile "Arn")}
      :ImageId (ref :AMI)
      :InstanceType (ref :InstanceType)
      :KeyName (fn-if :HasKeyName
                      (ref :KeyName)
                      no-value)
      :Monitoring {:Enabled true}
      :SecurityGroupIds [(ref :DatapubSecurityGroup)]
      :UserData
      (user-data
       "#!/usr/bin/env bash\n"
       "set -oeux \n"

       "echo \""
       "{:files-domain-name \\\"" (ref :FilesDomainName) "\\\"\n"
       " :postgres {:host \\\"" (get-att :RDSInstance "Endpoint.Address") "\\\"\n"
       "            :port " (get-att :RDSInstance "Endpoint.Port") "\n"
       "            :user \\\"postgres\\\"}\n"
       " :s3 {:datapub-bucket {:name \\\"" (ref :DatapubBucket) "\\\"}}\n"
       " :secrets\n"
       " {:postgres {:password {:secrets-manager/arn \\\"" (ref :RDSMasterCredentials) "\\\"\n"
       "                        :secrets-manager/key :password}}\n"
       "  :sysrev-dev-key {:secrets-manager/arn \\\"" (ref :SysrevDevKey) "\\\"\n"
       "                   :secrets-manager/key :key}\n"
       " }\n"
       "}\" > /home/admin/datapub/datapub-config.local.edn\n"

       ;; Adapted from https://github.com/awslabs/aws-cloudformation-templates/blob/2415d1dd34bdbf50e3b009879f6ba754a043afdf/aws/services/AutoScaling/AutoScalingRollingUpdates.yaml#L384-L387
       "state=\n"
       "until [ \"$state\" == \"\\\"healthy\\\"\" ]; do sleep 10;\n"
       "state=$(aws --region " region
       " elbv2 describe-target-health"
       " --target-group-arn " (ref :LoadBalancerTargetGroup)
       " --targets Id=$(ec2metadata --instance-id)"
       " --query TargetHealthDescriptions[0].TargetHealth.State); done\n"
       "cfn-signal -s true "
       " --stack " stack-name
       " --resource AutoScalingGroup"
       " --region " region)}}}

   :AutoScalingGroup
   {:Type "AWS::AutoScaling::AutoScalingGroup"
    :CreationPolicy
    {:AutoScalingCreationPolicy
     {:MinSuccessfulInstancesPercent 90}
     :ResourceSignal
     {:Timeout "PT10M"}}
    :UpdatePolicy
    {:AutoScalingReplacingUpdate
     {:WillReplace true}}
    :Properties
    {:Cooldown "30"
     :HealthCheckType "ELB"
     :HealthCheckGracePeriod 450
     :LaunchTemplate
     {:LaunchTemplateId (ref :LaunchTemplate)
      :Version (get-att :LaunchTemplate "LatestVersionNumber")}
     :MaxSize (ref :AutoScalingMaxSize)
     :MinSize (ref :AutoScalingMinSize)
     :Tags
     [{:Key "Name"
       :PropagateAtLaunch true
       :Value "Datapub"}]
     :TargetGroupARNs [(ref :LoadBalancerTargetGroup)]
     :VPCZoneIdentifier
     (split "," (ref :VpcSubnetIds))}}

   :AutoScalingPolicy
   {:Type "AWS::AutoScaling::ScalingPolicy"
    :Properties
    {:PolicyType "TargetTrackingScaling"
     :AutoScalingGroupName (ref :AutoScalingGroup)
     :TargetTrackingConfiguration
     {:PredefinedMetricSpecification
      {:PredefinedMetricType "ASGAverageCPUUtilization"}
      :TargetValue 50.0}}}})
