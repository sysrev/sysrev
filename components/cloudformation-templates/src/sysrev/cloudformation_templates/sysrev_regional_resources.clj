(ns sysrev.cloudformation-templates.sysrev-regional-resources
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(def ^{:doc "ELB Account Id for us-east-1"} elb-account-id 127311923021)

(def subnets
  (fn-if "6AZs"
         [(ref :SubnetA) (ref :SubnetB) (ref :SubnetC)
          (ref :SubnetD) (ref :SubnetE) (ref :SubnetF)]
         (fn-if "5AZs"
                [(ref :SubnetA) (ref :SubnetB) (ref :SubnetC)
                 (ref :SubnetD) (ref :SubnetE)]
                (fn-if "4AZs"
                       [(ref :SubnetA) (ref :SubnetB) (ref :SubnetC)
                        (ref :SubnetD)]
                       (fn-if "3AZs"
                              [(ref :SubnetA) (ref :SubnetB) (ref :SubnetC)]
                              [(ref :SubnetA) (ref :SubnetB)])))))

(deftemplate template
  :Description
  "This template creates the regional resources needed by Sysrev services."

  :Parameters
  {:CloudFrontOAI {:Type "String"}
   :DatapubDomainName {:Type "String"}
   :DatapubHostedZoneId {:Type "AWS::Route53::HostedZone::Id"}
   :NumberOfAZs {:Type "Number" :Default 3 :MinValue 2 :MaxValue 6}}

  :Conditions
  {:3AZs
   (fn-or
    (equals 3 (ref :NumberOfAZs))
    (equals 4 (ref :NumberOfAZs))
    (equals 5 (ref :NumberOfAZs))
    (equals 6 (ref :NumberOfAZs)))
   :4AZs
   (fn-or
    (equals 4 (ref :NumberOfAZs))
    (equals 5 (ref :NumberOfAZs))
    (equals 6 (ref :NumberOfAZs)))
   :5AZs
   (fn-or
    (equals 5 (ref :NumberOfAZs))
    (equals 6 (ref :NumberOfAZs)))
   :6AZs (equals 6 (ref :NumberOfAZs))}

  :Resources
  {:Vpc
   {:Type "AWS::EC2::VPC"
    :Properties
    {:CidrBlock "10.0.0.0/16"
     :EnableDnsHostnames true
     :EnableDnsSupport true}}

   :VpcIpv6CidrBlock
   {:Type "AWS::EC2::VPCCidrBlock"
    :Properties
    {:AmazonProvidedIpv6CidrBlock true
     :VpcId (ref :Vpc)}}

   :InternetGateway
   {:Type "AWS::EC2::InternetGateway"}

   :GatewayAttachment
   {:Type "AWS::EC2::VPCGatewayAttachment"
    :Properties
    {:InternetGatewayId
     (ref :InternetGateway)
     :VpcId (ref :Vpc)}}

   :RouteTable
   {:Type "AWS::EC2::RouteTable"
    :Properties
    {:VpcId (ref :Vpc)}}

   :InternetRouteIpv4
   {:Type "AWS::EC2::Route"
    :Properties
    {:DestinationCidrBlock "0.0.0.0/0"
     :GatewayId (ref :InternetGateway)
     :RouteTableId (ref :RouteTable)}}

   :InternetRouteIpv6
   {:Type "AWS::EC2::Route"
    :Properties
    {:DestinationIpv6CidrBlock "::/0"
     :GatewayId (ref :InternetGateway)
     :RouteTableId (ref :RouteTable)}}

   :SubnetA
   {:Type "AWS::EC2::Subnet"
    :DependsOn "VpcIpv6CidrBlock"
    :Properties
    {:AvailabilityZone (select 0 (get-azs))
     :CidrBlock
     (select 0 (cidr (get-att :Vpc "CidrBlock") 6 12))
     :Ipv6CidrBlock
     (select 0 (cidr (select 0 (get-att :Vpc "Ipv6CidrBlocks")) 6 64))
     :MapPublicIpOnLaunch true
     :VpcId (ref :Vpc)}}

   :SubnetARouteTableAssociation
   {:Type "AWS::EC2::SubnetRouteTableAssociation"
    :Properties
    {:RouteTableId (ref :RouteTable)
     :SubnetId (ref :SubnetA)}}

   :SubnetB
   {:Type "AWS::EC2::Subnet"
    :DependsOn "VpcIpv6CidrBlock"
    :Properties
    {:AvailabilityZone (select 1 (get-azs))
     :CidrBlock
     (select 1 (cidr (get-att :Vpc "CidrBlock") 6 12))
     :Ipv6CidrBlock
     (select 1 (cidr (select 0 (get-att :Vpc "Ipv6CidrBlocks")) 6 64))
     :MapPublicIpOnLaunch true
     :VpcId (ref :Vpc)}}

   :SubnetBRouteTableAssociation
   {:Type "AWS::EC2::SubnetRouteTableAssociation"
    :Properties
    {:RouteTableId (ref :RouteTable)
     :SubnetId (ref :SubnetB)}}

   :SubnetC
   {:Type "AWS::EC2::Subnet"
    :Condition "3AZs"
    :DependsOn "VpcIpv6CidrBlock"
    :Properties
    {:AvailabilityZone (select 2 (get-azs))
     :CidrBlock
     (select 2 (cidr (get-att :Vpc "CidrBlock") 6 12))
     :Ipv6CidrBlock
     (select 2 (cidr (select 0 (get-att :Vpc "Ipv6CidrBlocks")) 6 64))
     :MapPublicIpOnLaunch true
     :VpcId (ref :Vpc)}}

   :SubnetCRouteTableAssociation
   {:Type "AWS::EC2::SubnetRouteTableAssociation"
    :Condition "3AZs"
    :Properties
    {:RouteTableId (ref :RouteTable)
     :SubnetId (ref :SubnetC)}}

   :SubnetD
   {:Type "AWS::EC2::Subnet"
    :Condition "4AZs"
    :DependsOn "VpcIpv6CidrBlock"
    :Properties
    {:AvailabilityZone (select 3 (get-azs))
     :CidrBlock
     (select 3 (cidr (get-att :Vpc "CidrBlock") 6 12))
     :Ipv6CidrBlock
     (select 3 (cidr (select 0 (get-att :Vpc "Ipv6CidrBlocks")) 6 64))
     :MapPublicIpOnLaunch true
     :VpcId (ref :Vpc)}}

   :SubnetDRouteTableAssociation
   {:Type "AWS::EC2::SubnetRouteTableAssociation"
    :Condition "4AZs"
    :Properties
    {:RouteTableId (ref :RouteTable)
     :SubnetId (ref :SubnetD)}}

   :SubnetE
   {:Type "AWS::EC2::Subnet"
    :Condition "5AZs"
    :DependsOn "VpcIpv6CidrBlock"
    :Properties
    {:AvailabilityZone (select 4 (get-azs))
     :CidrBlock
     (select 4 (cidr (get-att :Vpc "CidrBlock") 6 12))
     :Ipv6CidrBlock
     (select 4 (cidr (select 0 (get-att :Vpc "Ipv6CidrBlocks")) 6 64))
     :MapPublicIpOnLaunch true
     :VpcId (ref :Vpc)}}

   :SubnetERouteTableAssociation
   {:Type "AWS::EC2::SubnetRouteTableAssociation"
    :Condition "5AZs"
    :Properties
    {:RouteTableId (ref :RouteTable)
     :SubnetId (ref :SubnetE)}}

   :SubnetF
   {:Type "AWS::EC2::Subnet"
    :Condition "6AZs"
    :DependsOn "VpcIpv6CidrBlock"
    :Properties
    {:AvailabilityZone (select 5 (get-azs))
     :CidrBlock
     (select 5 (cidr (get-att :Vpc "CidrBlock") 6 12))
     :Ipv6CidrBlock
     (select 5 (cidr (select 0 (get-att :Vpc "Ipv6CidrBlocks")) 6 64))
     :MapPublicIpOnLaunch true
     :VpcId (ref :Vpc)}}

   :SubnetFRouteTableAssociation
   {:Type "AWS::EC2::SubnetRouteTableAssociation"
    :Condition "6AZs"
    :Properties
    {:RouteTableId (ref :RouteTable)
     :SubnetId (ref :SubnetF)}}

   :LoggingBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "Private"
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}}}

   :LoggingBucketReadPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:Groups ["sysrev-developers"]
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["s3:GetBucketLocation" "s3:ListBucket"]
        :Effect "Allow"
        :Resource (join "" ["arn:aws:s3:::" (ref :LoggingBucket)])}
       {:Action "s3:GetObject"
        :Effect "Allow"
        :Resource (join "" ["arn:aws:s3:::" (ref :LoggingBucket) "/*"])}]}}}

   :LoggingBucketELBWritePolicy
   {:Type "AWS::S3::BucketPolicy"
    :Properties
    {:Bucket (ref :LoggingBucket)
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action "s3:PutObject"
        :Effect "Allow"
        :Principal {:AWS (str "arn:aws:iam::" elb-account-id ":root")}
        :Resource (join "" ["arn:aws:s3:::" (ref :LoggingBucket) "/AWSLogs/" account-id "/*"])}
       {:Action "s3:PutObject"
        :Condition {:StringEquals {"s3:x-amz-acl" "bucket-owner-full-control"}}
        :Effect "Allow"
        :Principal {:Service "delivery.logs.amazonaws.com"}
        :Resource (join "" ["arn:aws:s3:::" (ref :LoggingBucket) "/AWSLogs/" account-id "/*"])}
       {:Action "s3:GetBucketAcl"
        :Effect "Allow"
        :Principal {:Service "delivery.logs.amazonaws.com"}
        :Resource (join "" ["arn:aws:s3:::" (ref :LoggingBucket)])}]}}}

   :CredentialsKey
   {:Type "AWS::KMS::Key"
    :Properties
    {:KeyPolicy
     {:Version "2012-10-17"
      :Statement
      {:Effect "Allow"
       :Principal
       {:AWS (sub "arn:aws:iam::${AWS::AccountId}:root")}
       :Action "kms:*"
       :Resource "*"}}}}

   :CredentialsKeyUsePolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      {:Effect "Allow"
       :Action ["kms:Encrypt" "kms:Decrypt"]
       :Resource
       [(join "" ["arn:aws:kms:" region ":" account-id ":key/" (ref :CredentialsKey)])]}}}}

   :LogsKey
   {:Type "AWS::KMS::Key"
    :Properties
    {:KeyPolicy
     {:Version "2012-10-17"
      :Statement
      [{:Action "kms:*"
        :Effect "Allow"
        :Principal {:AWS (sub "arn:aws:iam::${AWS::AccountId}:root")}
        :Resource "*"}
       {:Action ["kms:Encrypt*"
                 "kms:Decrypt*"
                 "kms:ReEncrypt*"
                 "kms:GenerateDataKey*"
                 "kms:Describe*"]
        :Condition
        {:ArnLike
         {"kms:EncryptionContext:aws:logs:arn" (sub "arn:aws:logs:${AWS::Region}:${AWS::AccountId}:*")}}
        :Effect "Allow"
        :Principal {:Service (sub "logs.${AWS::Region}.amazonaws.com")}
        :Resource "*"}]}}}

   :LoadBalancerSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "HTTP/S ALBs"
     :SecurityGroupIngress
     [{:CidrIp "0.0.0.0/0" :IpProtocol "tcp" :FromPort 80 :ToPort 80}
      {:CidrIpv6 "::/0" :IpProtocol "tcp" :FromPort 80 :ToPort 80}
      {:CidrIp "0.0.0.0/0" :IpProtocol "tcp" :FromPort 443 :ToPort 443}
      {:CidrIpv6 "::/0" :IpProtocol "tcp" :FromPort 443 :ToPort 443}]
     :VpcId (ref :Vpc)}}

   :LoadBalancer
   {:Type "AWS::ElasticLoadBalancingV2::LoadBalancer"
    :DependsOn ["LoggingBucketELBWritePolicy"]
    :Properties
    {:IpAddressType "dualstack"
     :LoadBalancerAttributes
     [{:Key "access_logs.s3.enabled" :Value "true"}
      {:Key "access_logs.s3.bucket" :Value (ref :LoggingBucket)}]
     :SecurityGroups
     [(get-att :Vpc "DefaultSecurityGroup")
      (ref :LoadBalancerSecurityGroup)]
     :Subnets subnets
     :Type "application"}}

   :LoadBalancerHTTPListener
   {:Type "AWS::ElasticLoadBalancingV2::Listener"
    :Properties
    {:LoadBalancerArn (ref :LoadBalancer)
     :Port 80
     :Protocol "HTTP"
     :DefaultActions
     [{:Type "redirect"
       :RedirectConfig
       {:Protocol "HTTPS"
        :Port "443"
        :Host "#{host}"
        :Path "/#{path}"
        :Query "#{query}"
        :StatusCode "HTTP_301"}}]}}

   :DatapubCertificate
   {:Type "AWS::CertificateManager::Certificate"
    :Properties
    {:DomainName (ref :DatapubDomainName)
     :DomainValidationOptions
     [{:DomainName (ref :DatapubDomainName)
       :HostedZoneId (ref :DatapubHostedZoneId)}]
     :ValidationMethod "DNS"}}

   :LoadBalancerHTTPSListener
   {:Type "AWS::ElasticLoadBalancingV2::Listener"
    :Properties
    {:Certificates [{:CertificateArn (ref :DatapubCertificate)}]
     :DefaultActions
     [{:FixedResponseConfig
       {:ContentType "text/plain"
        :MessageBody "503 Service Unavailable\n\nNo targets configured for this domain."
        :StatusCode "503"}
       :Type "fixed-response"}]
     :LoadBalancerArn (ref :LoadBalancer)
     :Port 443
     :Protocol "HTTPS"}}

   :RDSSubnetGroup
   {:Type "AWS::RDS::DBSubnetGroup"
    :Properties
    {:DBSubnetGroupDescription "Sysrev regional resources"
     :SubnetIds subnets}}}

  :Outputs
  (prefixed-outputs
   "${AWS::StackName}-"
   {:CloudFrontOAI [(ref :CloudFrontOAI)]
    :CredentialsKeyId [(ref :CredentialsKey)]
    :CredentialsKeyUsePolicyArn [(ref :CredentialsKeyUsePolicy)]
    :DatapubDomainName [(ref :DatapubDomainName)]
    :DatapubHostedZoneId [(ref :DatapubHostedZoneId)]
    :LoadBalancerArn [(ref :LoadBalancer)]
    :LoadBalancerCanonicalHostedZoneId [(get-att :LoadBalancer "CanonicalHostedZoneID")]
    :LoadBalancerDNSName [(get-att :LoadBalancer "DNSName")]
    :LoadBalancerHTTPSListenerArn [(ref :LoadBalancerHTTPSListener)]
    :LoadBalancerName [(get-att :LoadBalancer "LoadBalancerName")]
    :LoadBalancerSecurityGroupId [(ref :LoadBalancerSecurityGroup)]
    :LogsKeyArn [(arn :LogsKey)]
    :RDSSubnetGroupName [(ref :RDSSubnetGroup)]
    :VpcId [(ref :Vpc)]
    :VpcSubnetIds [(join "," subnets)]}))

(comment
  (write-template "components/cloudformation-templates/out/sysrev-regional-resources.template"
                  template))
