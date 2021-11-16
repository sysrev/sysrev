(ns sysrev.cloudformation-templates.sysrev-global-resources
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(deftemplate template
  :Description
  "This template creates the global resources needed by Sysrev services."

  :Resources
  {:AdminAccessCloudFormationServiceRole
   {:Type "AWS::IAM::Role"
    :Properties
    {:AssumeRolePolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["sts:AssumeRole"]
        :Effect "Allow"
        :Principal {:Service "cloudformation.amazonaws.com"}}]}
     :ManagedPolicyArns ["arn:aws:iam::aws:policy/AdministratorAccess"]
     :RoleName "AdminAccessCloudFormationServiceRole"}}

   :AdminAccessCloudFormationServicePassRolePolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["iam:PassRole"]
        :Effect "Allow"
        :Resource (arn :AdminAccessCloudFormationServiceRole)}]}}}

   :CloudFormationCreateUpdatePolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["cloudformation:*"]
        :Effect "Allow"
        :Resource "*"}
       {:Action ["cloudformation:DeleteStack"]
        :Effect "Deny"
        :Resource "*"}]}}}

   ;; https://www.packer.io/docs/builders/amazon
   :PackerBuildPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action
        ["ec2:AttachVolume"
         "ec2:AuthorizeSecurityGroupIngress"
         "ec2:CopyImage"
         "ec2:CreateImage"
         "ec2:CreateKeypair"
         "ec2:CreateSecurityGroup"
         "ec2:CreateSnapshot"
         "ec2:CreateTags"
         "ec2:CreateVolume"
         "ec2:DeleteKeyPair"
         "ec2:DeleteSecurityGroup"
         "ec2:DeleteSnapshot"
         "ec2:DeleteVolume"
         "ec2:DeregisterImage"
         "ec2:DescribeImageAttribute"
         "ec2:DescribeImages"
         "ec2:DescribeInstances"
         "ec2:DescribeInstanceStatus"
         "ec2:DescribeRegions"
         "ec2:DescribeSecurityGroups"
         "ec2:DescribeSnapshots"
         "ec2:DescribeSubnets"
         "ec2:DescribeTags"
         "ec2:DescribeVolumes"
         "ec2:DetachVolume"
         "ec2:GetPasswordData"
         "ec2:ModifyImageAttribute"
         "ec2:ModifyInstanceAttribute"
         "ec2:ModifySnapshotAttribute"
         "ec2:RegisterImage"
         "ec2:RunInstances"
         "ec2:StopInstances"
         "ec2:TerminateInstances"]
        :Effect "Allow"
        :Resource "*"}]}}}

   :GitHubActionsUser
   {:Type "AWS::IAM::User"
    :Properties
    {:ManagedPolicyArns
     [(ref :AdminAccessCloudFormationServicePassRolePolicy)
      (ref :CloudFormationCreateUpdatePolicy)
      (ref :PackerBuildPolicy)]
     :UserName "github-actions"}}

   :DatapubBucket
   {:Type "AWS::S3::Bucket"
    :DeletionPolicy "Retain"
    :Properties
    {:AccessControl "Private"
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}
     :Tags
     (tags :grant "thrive")}}}

  :Outputs
  (prefixed-outputs
   "${AWS::StackName}-"
   {:AdminAccessCloudFormationServiceRoleArn [(arn :AdminAccessCloudFormationServiceRole)]
    :DatapubBucket [(ref :DatapubBucket)]}))

(comment
  (write-template "components/cloudformation-templates/out/sysrev-global-resources.template"
                  template))
