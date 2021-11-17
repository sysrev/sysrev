(ns sysrev.cloudformation-templates.datapub
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(defn import-regional [export-name]
  (import-value (str "Sysrev-Regional-Resources-" export-name)))

(deftemplate template
  :Description
  "This template creates the Datapub AutoScalingGroup."

  :Parameters
  {:AMI {:Type "AWS::EC2::Image::Id"}
   :DatapubBucket {:MaxLength 63
                   :MinLength 3
                   :Type "String"}
   :KeyName {:Default ""
             :Type "String"}}

  :Conditions
  {:HasKeyName
   (fn-not (equals "" (ref :KeyName)))}

  :Resources
  {:RDSMasterCredentials
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:GenerateSecretString
     {:ExcludeCharacters "\"@/\\"
      :GenerateStringKey "password"
      :PasswordLength 32
      :SecretStringTemplate "{\"username\": \"postgres\"}"}
     :KmsKeyId (import-regional "CredentialsKeyId")}}

   :SysrevDevKey
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:GenerateSecretString
     {:GenerateStringKey "key"
      :PasswordLength 32
      :SecretStringTemplate "{}"}
     :KmsKeyId (import-regional "CredentialsKeyId")}}

   :DatapubSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Datapub Servers"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 8121
       :ToPort 8121
       :SourceSecurityGroupId (import-regional "LoadBalancerSecurityGroupId")}]
     :VpcId (import-regional "VpcId")}}

   :RDSSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Datapub RDS Instances"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 5432
       :ToPort 5432
       :SourceSecurityGroupId (ref :DatapubSecurityGroup)}]
     :VpcId (import-regional "VpcId")}}

   :RDSInstance
   {:Type "AWS::RDS::DBInstance"
    :DeletionPolicy "Snapshot"
    :UpdateReplacePolicy "Snapshot"
    :Properties
    {:AllocatedStorage "500"
     :AllowMajorVersionUpgrade true
     :AutoMinorVersionUpgrade true
     :BackupRetentionPeriod 7
     :CopyTagsToSnapshot true
     :DBInstanceClass "db.m6g.large"
     :DBInstanceIdentifier "datapubio"
     :DBName "datapub"
     :DBSubnetGroupName (import-regional "RDSSubnetGroupName")
     :DeletionProtection true
     :EnablePerformanceInsights true
     :Engine "postgres"
     :EngineVersion "13.3"
     :Iops 3000
     :MasterUsername (sub "{{resolve:secretsmanager:${RDSMasterCredentials}::username}}")
     :MasterUserPassword (sub "{{resolve:secretsmanager:${RDSMasterCredentials}::password}}")
     :MaxAllocatedStorage 1000
     :MultiAZ false
     :PreferredBackupWindow "08:48-09:18"
     :PreferredMaintenanceWindow "Sun:05:57-Sun:06:27"
     :PubliclyAccessible false
     :StorageEncrypted true
     :Tags (tags :grant "1R43DA052916-01")
     :VPCSecurityGroups [(ref :RDSSecurityGroup)]}}

   :RecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (import-regional "DatapubHostedZoneId")
     :RecordSets
     [{:AliasTarget
       {:HostedZoneId (import-regional "LoadBalancerCanonicalHostedZoneId")
        :DNSName (import-regional "LoadBalancerDNSName")}
       :Name (import-regional "DatapubDomainName")
       :Type "A"}
      {:AliasTarget
       {:HostedZoneId (import-regional "LoadBalancerCanonicalHostedZoneId")
        :DNSName (import-regional "LoadBalancerDNSName")}
       :Name (import-regional "DatapubDomainName")
       :Type "AAAA"}]}}

   :LoadBalancerTargetGroup
   {:Type "AWS::ElasticLoadBalancingV2::TargetGroup"
    :Properties
    {:HealthCheckPath "/health"
     :HealthCheckProtocol "HTTP"
     :Matcher {:HttpCode "200-299"}
     :Port 8121
     :Protocol "HTTP"
     :TargetType "instance"
     :VpcId (import-regional "VpcId")}}

   :LoadBalancerListenerRule
   {:Type "AWS::ElasticLoadBalancingV2::ListenerRule"
    :Properties
    {:Actions
     [{:TargetGroupArn (ref :LoadBalancerTargetGroup)
       :Type "forward"}]
     :Conditions
     [{:Field "host-header"
       :HostHeaderConfig {:Values [(import-regional "DatapubDomainName")]}}]
     :ListenerArn (import-regional "LoadBalancerHTTPSListenerArn")
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
      (ref :DatapubBucketFullAccessPolicy)
      (ref :InstancePolicy)
      (import-regional "CredentialsKeyUsePolicyArn")]}}

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
      :InstanceType "t3a.small"
      :KeyName (fn-if :HasKeyName
                      (ref :KeyName)
                      no-value)
      :MetadataOptions {:HttpTokens "required"}
      :Monitoring {:Enabled true}
      :SecurityGroupIds [(ref :DatapubSecurityGroup)]
      :UserData
      (user-data
       "#!/usr/bin/env bash\n"
       "set -oeux \n"
       ;; Adapted from https://github.com/awslabs/aws-cloudformation-templates/blob/2415d1dd34bdbf50e3b009879f6ba754a043afdf/aws/services/AutoScaling/AutoScalingRollingUpdates.yaml#L384-L387
       ;"state=\n"
       ;"until [ \"$state\" == \"\\\"healthy\\\"\" ]; do sleep 10;\n"
       ;"state=$(aws --region " region
       ;" elbv2 describe-target-health"
       ;" --target-group-arn " (ref :LoadBalancerTargetGroup)
       ;" --targets Id=$(ec2metadata --instance-id)"
       ;" --query TargetHealthDescriptions[0].TargetHealth.State); done\n"
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
     {:Timeout "PT5M"}}
    :UpdatePolicy
    {:AutoScalingReplacingUpdate
     {:WillReplace true}}
    :Properties
    {:Cooldown "30"
     :HealthCheckType "ELB"
     :HealthCheckGracePeriod 300
     :LaunchTemplate
     {:LaunchTemplateId (ref :LaunchTemplate)
      :Version (get-att :LaunchTemplate "LatestVersionNumber")}
     :MaxSize "2"
     :MinSize "1"
     :Tags
     [{:Key "Name"
       :PropagateAtLaunch true
       :Value "Datapub"}]
     :TargetGroupARNs [(ref :LoadBalancerTargetGroup)]
     :VPCZoneIdentifier
     (split "," (import-regional "VpcSubnetIds"))}}

   :AutoScalingPolicy
   {:Type "AWS::AutoScaling::ScalingPolicy"
    :Properties
    {:PolicyType "TargetTrackingScaling"
     :AutoScalingGroupName (ref :AutoScalingGroup)
     :TargetTrackingConfiguration
     {:PredefinedMetricSpecification
      {:PredefinedMetricType "ASGAverageCPUUtilization"}
      :TargetValue 50.0}}}})

(comment
  (write-template "components/cloudformation-templates/out/datapub.template"
                  template))
