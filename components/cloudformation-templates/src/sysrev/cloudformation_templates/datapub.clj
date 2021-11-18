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
   :AutoScalingMaxSize {:AllowedPattern "[1-9][0-9]*"
                        :Type "String"}
   :AutoScalingMinSize {:AllowedPattern "[1-9][0-9]*"
                        :Type "String"}
   :DatapubBucket {:MaxLength 63
                   :MinLength 3
                   :Type "String"}
   :KeyName {:Default ""
             :Type "String"}
   :RDSAllocatedStorage {:AllowedPattern "[1-9][0-9][0-9]+"
                         :Description "Minimum allocated storage in GB. Must be at least 100."
                         :Type "String"}
   :RDSInstanceClass {:Type "String"}
   :RDSIops {:MinValue 1000
             :Type "Number"}}

  :Conditions
  {:HasKeyName
   (fn-not (equals "" (ref :KeyName)))}

  :Resources
  {:LogGroup
   {:Type "AWS::Logs::LogGroup"
    :Properties
    {:KmsKeyId (import-regional "LogsKeyArn")
     :LogGroupName "Datapub-Servers"
     :RetentionInDays 14}}

   :RDSMasterCredentials
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
       :FromPort 8888
       :ToPort 8888
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
    {:AllocatedStorage (ref :RDSAllocatedStorage)
     :AllowMajorVersionUpgrade true
     :AutoMinorVersionUpgrade true
     :BackupRetentionPeriod 7
     :CopyTagsToSnapshot true
     :DBInstanceClass (ref :RDSInstanceClass)
     :DBInstanceIdentifier "datapubio"
     :DBName "datapub"
     :DBSubnetGroupName (import-regional "RDSSubnetGroupName")
     :DeletionProtection true
     :EnablePerformanceInsights true
     :Engine "postgres"
     :EngineVersion "13.3"
     :Iops (ref :RDSIops)
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
     :Port 8888
     :Protocol "HTTP"
     :ProtocolVersion "HTTP2"
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
      "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
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
      :Monitoring {:Enabled true}
      :SecurityGroupIds [(ref :DatapubSecurityGroup)]
      :UserData
      (user-data
       "#!/usr/bin/env bash\n"
       "set -oeux \n"

       "echo \""
       "{:postgres {:host \\\"" (get-att :RDSInstance "Endpoint.Address") "\\\"\n"
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
     :HealthCheckGracePeriod 300
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
