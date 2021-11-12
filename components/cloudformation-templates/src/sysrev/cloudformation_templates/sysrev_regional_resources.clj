(ns sysrev.cloudformation-templates.sysrev-regional-resources
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

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
  {:DatapubDomainName {:Type "String"}
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

   :CredentialsKeyAlias
   {:Type "AWS::KMS::Alias"
    :Properties
    {:AliasName "alias/Sysrev-Credentials-Key"
     :TargetKeyId (ref :CredentialsKey)}}

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
    :Properties
    {:IpAddressType "dualstack"
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
     :Protocol "HTTPS"}}}

  :Outputs
  (prefixed-outputs
   "${AWS::StackName}-"
   {:DatapubDomainName [(ref :DatapubDomainName)]
    :DatapubHostedZoneId [(ref :DatapubHostedZoneId)]
    :LoadBalancerArn [(ref :LoadBalancer)]
    :LoadBalancerCanonicalHostedZoneId [(get-att :LoadBalancer "CanonicalHostedZoneID")]
    :LoadBalancerDNSName [(get-att :LoadBalancer "DNSName")]
    :LoadBalancerHTTPSListenerArn [(ref :LoadBalancerHTTPSListener)]
    :LoadBalancerName [(get-att :LoadBalancer "LoadBalancerName")]
    :LoadBalancerSecurityGroupId [(ref :LoadBalancerSecurityGroup)]
    :VpcId [(ref :Vpc)]
    :VpcSubnetIds [(join "," subnets)]}))

(comment
  (write-template "components/cloudformation-templates/out/sysrev-regional-resources.template"
                  template))
