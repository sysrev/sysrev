(ns sysrev.cloudformation-templates.sysrev
  (:refer-clojure :exclude [ref])
  (:require [clojure.java.io :as io]
            [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(defn import-regional [export-name]
  (import-value (str "Sysrev-Regional-Resources-" export-name)))

(deftemplate template
  :Description
  "This template creates the Sysrev AutoScalingGroup."

  :Parameters
  {:RDSAllocatedStorage {:AllowedPattern "[1-9][0-9]+"
                         :Description "Minimum allocated storage in GB. Must be at least 20."
                         :Type "String"}
   :RDSInstanceClass {:Type "String"}
   :RDSIops {:Default 1000
             :Description "Provisioned Iops for the storage. Only used when RDSStorageType is io1."
             :MinValue 1000
             :Type "Number"}
   :RDSStorageType {:AllowedPattern "(gp2)|(io1)"
                    :Type "String"}}

  :Conditions
  {:RDSIo1Storage
   (equals "io1" (ref :RDSStorageType))}

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

   :SysrevSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Sysrev Servers"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 4041
       :ToPort 4041
       :SourceSecurityGroupId (import-regional "LoadBalancerSecurityGroupId")}]
     :VpcId (import-regional "VpcId")}}

   :RDSSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Sysrev RDS Instances"
     :SecurityGroupIngress
     [{:IpProtocol "tcp"
       :FromPort 5432
       :ToPort 5432
       :SourceSecurityGroupId (ref :SysrevSecurityGroup)}]
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
     :DBInstanceIdentifier "sysrev"
     :DBName "sysrev"
     :DBSubnetGroupName (import-regional "RDSSubnetGroupName")
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

(comment
  (write-template "components/cloudformation-templates/out/sysrev.template"
                  template))
