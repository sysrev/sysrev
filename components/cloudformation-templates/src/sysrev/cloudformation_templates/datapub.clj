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
   :KeyName {:Default ""
             :Type "AWS::EC2::KeyPair::KeyName"}}

  :Conditions
  {:HasKeyName
   (fn-not (equals "" (ref :KeyName)))}

  :Resources
  {:RecordSetGroup
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

   :SecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Datapub Servers"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 8121
       :ToPort 8121
       :SourceSecurityGroupId (import-regional "LoadBalancerSecurityGroupId")}]
     :VpcId (import-regional "VpcId")}}

   :InstancePolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["elasticloadbalancing:DescribeTargetHealth"]
        :Effect "Allow"
        :Resource (import-regional "LoadBalancerArn")}]}}}

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
      (ref :InstancePolicy)]}}

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
      :SecurityGroupIds [(ref :SecurityGroup)]
      :UserData
      (user-data
       "#!/usr/bin/env bash\n"
       "set -oeux \n"
       "cfn-signal -s true "
       " --stack " stack-name
       " --resource AutoScalingGroup"
       " --region " region)}}}

   :AutoScalingGroup
   {:Type "AWS::AutoScaling::AutoScalingGroup"
    :UpdatePolicy
    {:AutoScalingRollingUpdate
     {:MaxBatchSize 1
      :MinInstancesInService 1
      :MinSuccessfulInstancesPercent 90
      :PauseTime "PT5M"
      :WaitOnResourceSignals true}}
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
