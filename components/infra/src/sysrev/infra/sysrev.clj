(ns sysrev.infra.sysrev
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(defn public-port [port]
  [{:CidrIp "0.0.0.0/0"
    :IpProtocol "tcp"
    :FromPort port
    :ToPort port}
   {:CidrIpv6 "::/0"
    :IpProtocol "tcp"
    :FromPort port
    :ToPort port}])

(deftemplate template
  :Description
  "This template creates the Sysrev AutoScalingGroup."

  :Parameters
  {:CredentialsGroupName {:Type "String"}
   :CredentialsKeyId {:Type "String"}
   :VpcId {:Type "String"}}

  :Resources
  {:DatasourceCredentials
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:KmsKeyId (ref :CredentialsKeyId)
     :SecretString "{\"token\":\"\"}"}}

   :PayPalCredentials
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:KmsKeyId (ref :CredentialsKeyId)
     :SecretString "{\"client-id\":\"\",\"secret\":\"\",\"url\":\"\"}"}}

   :StripeCredentials
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:KmsKeyId (ref :CredentialsKeyId)
     :SecretString "{\"public-key\":\"\",\"secret-key\":\"\"}"}}

   :SysrevDevKey
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:KmsKeyId (ref :CredentialsKeyId)
     :SecretString "{\"token\":\"\"}"}}

   :CredentialsPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:Groups [(ref :CredentialsGroupName)]
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource [(ref :DatasourceCredentials)
                   (ref :PayPalCredentials)
                   (ref :StripeCredentials)
                   (ref :SysrevDevKey)]}]}}}

   :SysrevSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Sysrev Servers"
     :SecurityGroupIngress
     (mapcat public-port [22 80 443])
     :VpcId (ref :VpcId)}}

   :ServerLifecyclePolicy
   {:Type "AWS::DLM::LifecyclePolicy"
    :Properties
    {:Description "Sysrev Backups"
     :ExecutionRoleArn
     (sub ["arn:aws:iam::${AccountId}:role/service-role/AWSDataLifecycleManagerDefaultRole"
           {:AccountId account-id}])
     :PolicyDetails
     {:ResourceTypes ["INSTANCE"]
      :Schedules
      [{:Name "Daily Schedule"
        :CopyTags true
        :CreateRule {:Interval 24 :IntervalUnit "HOURS" :Times ["06:00"]}
        :RetainRule {:Count 7}
        :VariableTags [{:Key "instance-id" :Value "$(instance-id)"}]}
       {:Name "Weekly Schedule"
        :CopyTags true
        :CreateRule {:CronExpression "cron(0 6 ? * SUN *)"}
        :RetainRule {:Count 4}
        :VariableTags [{:Key "instance-id" :Value "$(instance-id)"}]}
       {:Name "Monthly Schedule"
        :CopyTags true
        :CreateRule {:CronExpression "cron(0 6 1 * ? *)"}
        :RetainRule {:Count 12}
        :VariableTags [{:Key "instance-id" :Value "$(instance-id)"}]}]
      :TargetTags [{:Key "Name" :Value "sysrev-t3"}]}
     :State "ENABLED"}}})
