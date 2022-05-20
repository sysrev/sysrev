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
  {:CredentialsKeyId {:Type "String"}
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
   :VpcId {:Type "String"}}

  :Conditions
  {:RDSIo1Storage
   (equals "io1" (ref :RDSStorageType))}

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

   :RDSMasterCredentials
   {:Type "AWS::SecretsManager::Secret"
    :Properties
    {:GenerateSecretString
     {:ExcludeCharacters "\"@/\\"
      :GenerateStringKey "password"
      :PasswordLength 32
      :SecretStringTemplate "{\"username\": \"postgres\"}"}
     :KmsKeyId (ref :CredentialsKeyId)}}

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
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :DatasourceCredentials)}
       {:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :PayPalCredentials)}
       {:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :RDSMasterCredentials)}
       {:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :StripeCredentials)}
       {:Action ["secretsmanager:GetSecretValue"]
        :Effect "Allow"
        :Resource (ref :SysrevDevKey)}]}}}

   :SysrevSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Sysrev Servers"
     :SecurityGroupIngress
     (mapcat public-port [22 80 443])
     :VpcId (ref :VpcId)}}

   :RDSSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Sysrev RDS Instances"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 5432
       :ToPort 5432
       :SourceSecurityGroupId (ref :SysrevSecurityGroup)}]
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
     :DBInstanceIdentifier "sysrev"
     :DBName "sysrev"
     :DBSubnetGroupName (ref :RDSSubnetGroupName)
     :DeletionProtection true
     :EnablePerformanceInsights true
     :Engine "postgres"
     :EngineVersion "11.14"
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
     :VPCSecurityGroups [(ref :RDSSecurityGroup)]}}})
