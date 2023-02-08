(ns sysrev.infra.global
  (:refer-clojure :exclude [ref])
  (:require [io.staticweb.cloudformation-templating :refer :all :exclude [template]]))

(defn google-mx-recordset [domain-name]
  {:Name domain-name
   :ResourceRecords
   ["1 ASPMX.L.GOOGLE.COM"
    "5 ALT1.ASPMX.L.GOOGLE.COM"
    "5 ALT2.ASPMX.L.GOOGLE.COM"
    "10 ALT3.ASPMX.L.GOOGLE.COM"
    "10 ALT4.ASPMX.L.GOOGLE.COM"]
   :TTL "3600"
   :Type "MX"})

(defn no-email-recordsets
  "Protect domains that currently send no email from being used for spoofing.

  Important: Make sure there is also an entry \"v=spf1 -all\" in a TXT record
  for the domain apex.

  See https://www.gov.uk/guidance/protect-domains-that-dont-send-email"
  [domain-name]
  [{:Name domain-name
    :ResourceRecords ["0 ."]
    :TTL "3600"
    :Type "MX"}
   {:Name (join "." ["_dmarc" domain-name])
    :ResourceRecords ["\"v=DMARC1;p=reject;sp=reject;adkim=s;aspf=s;fo=1\""]
    :TTL "3600"
    :Type "TXT"}
   {:Name (join "." ["*._domainkey" domain-name])
    :ResourceRecords ["\"v=DKIM1; p=\""]
    :TTL "3600"
    :Type "TXT"}])

(defn simple-records [domain-name ttl type m]
  (->> (sort-by first m)
       (mapv
        (fn [[subdomain target]]
          {:Name (join "." [subdomain domain-name])
           :ResourceRecords [target]
           :TTL (str ttl)
           :Type type}))))

(deftemplate template
  :Description
  "This template creates the global resources needed by Sysrev services."

  :Parameters
  {:DatapubZoneApex {:Description "The DNS zone apex for Datapub with no final period, e.g., \"datapub.dev\""
                     :Type "String"}
   :Env {:AllowedPattern "(dev)|(prod)|(staging)"
         :Type "String"}
   :InsilicaZoneApex {:Description "The DNS zone apex for Insilica with no final period, e.g., \"insilica.co\""
                      :Type "String"}
   :SysrevZoneApex {:Description "The DNS zone apex for Sysrev with no final period, e.g., \"sysrev.com\""
                    :Type "String"}}

  :Conditions
  {:HasInsilicaZone
   (fn-not (equals "" (ref :InsilicaZoneApex)))
   :IsProd (equals "prod" (ref :Env))}

  :Resources
  {:DatapubHostedZone
   {:Type "AWS::Route53::HostedZone"
    :Properties
    {:Name (ref :DatapubZoneApex)}}

   :DatapubRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (ref :DatapubHostedZone)
     :RecordSets
     (concat
      [{:Name (ref :DatapubZoneApex)
        :ResourceRecords ["54.210.22.161"]
        :TTL "900"
        :Type "A"}
       {:Name (ref :DatapubZoneApex)
        :ResourceRecords ["\"v=spf1 -all\""]
        :TTL "900"
        :Type "TXT"}]
      (no-email-recordsets (ref :DatapubZoneApex)))}}

   :InsilicaHostedZone
   {:Type "AWS::Route53::HostedZone"
    :Condition "HasInsilicaZone"
    :Properties
    {:Name (ref :InsilicaZoneApex)}}

   :InsilicaRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Condition "HasInsilicaZone"
    :Properties
    {:HostedZoneId (ref :InsilicaHostedZone)
     :RecordSets
     (concat
      [(fn-if
        :IsProd
        {:Name (ref :InsilicaZoneApex)
         :ResourceRecords ["\"v=spf1 include:_spf.google.com ~all\""]
         :TTL "900"
         :Type "TXT"}
        no-value)
       {:AliasTarget {:DNSName "dalp9pupkphmg.cloudfront.net."
                      :HostedZoneId "Z2FDTNDATAQYW2"}
        :Name (ref :InsilicaZoneApex)
        :Type "A"}
       {:AliasTarget {:DNSName "dalp9pupkphmg.cloudfront.net."
                      :HostedZoneId "Z2FDTNDATAQYW2"}
        :Name (ref :InsilicaZoneApex)
        :Type "AAAA"}
       {:AliasTarget {:DNSName "s3-website-us-east-1.amazonaws.com."
                      :HostedZoneId "Z3AQBSTGFYJSTF"}
        :Name (join "." ["www" (ref :InsilicaZoneApex)])
        :Type "A"}
       {:AliasTarget {:DNSName "s3-website-us-east-1.amazonaws.com."
                      :HostedZoneId "Z3AQBSTGFYJSTF"}
        :Name (join "." ["www" (ref :InsilicaZoneApex)])
        :Type "AAAA"}]
      (simple-records
       (ref :InsilicaZoneApex) 900 "A"
       {"api" "52.203.104.249"
        "blog" "52.203.104.249"
        "datasource" "54.210.22.161"
        "db1" "54.234.183.33"
        "db2" "54.148.8.116"
        "db3" "52.89.182.113"
        "jenkins" "18.233.186.220"
        "k8s" "34.230.43.8"
        "oldat" "52.1.46.20"})
      (simple-records
       (ref :InsilicaZoneApex) 900 "CNAME"
       {"*.ws1" "insilica-ws-1.ddns.net"}))}}

   :InsilicaEmailRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Condition "HasInsilicaZone"
    :Properties
    {:HostedZoneId (ref :InsilicaHostedZone)
     :RecordSets
     (fn-if
      :IsProd
      [(google-mx-recordset (ref :InsilicaZoneApex))]
      (no-email-recordsets (ref :InsilicaZoneApex)))}}

   :SysrevHostedZone
   {:Type "AWS::Route53::HostedZone"
    :Properties
    {:Name (ref :SysrevZoneApex)}}

   :SysrevRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (ref :SysrevHostedZone)
     :RecordSets
     (concat
      [{:Name (ref :SysrevZoneApex)
        :ResourceRecords ["54.210.22.161"]
        :TTL "900"
        :Type "A"}
       {:Name (ref :SysrevZoneApex)
        :ResourceRecords
        ["\"google-site-verification=vieuiGhh2_y23yudDal67UXxBLsMTeB1DdGm0pCbvPI\""
         "\"google-site-verification=Vj_PTRD5B_p2WCrbtTN6QCKAVfLNGTtDXCewRUKCYeY\""
         (fn-if
          :IsProd
          "\"v=spf1 -all\""
          no-value)]
        :TTL "900"
        :Type "TXT"}
       {:Name (join "." ["_github-pages-challenge-sysrev" (ref :SysrevZoneApex)])
        :ResourceRecords ["\"c3d762f72d636152f716897fe47d67\""]
        :TTL "900"
        :Type "TXT"}]
      (simple-records
       (ref :SysrevZoneApex) 900 "A"
       {"analytics" "54.210.22.161"
        "blog" "54.210.22.161"
        "blog.staging" "52.1.46.20"
        "data" "23.227.38.32"
        "internal" "54.210.22.161"
        "pubmed" "54.210.22.161"
        "srplumber" "54.210.22.161"
        "staging" "52.1.46.20"
        "ws1" "71.114.106.111"
        "www" "54.210.22.161"})
      (simple-records
       (ref :SysrevZoneApex) 900 "CNAME"
       {"9253050" "sendgrid.net."
        "em680" "u9253050.wl051.sendgrid.net."
        "r" "sysrev.github.io."
        "s1._domainkey" "s1.domainkey.u9253050.wl051.sendgrid.net."
        "s2._domainkey" "s2.domainkey.u9253050.wl051.sendgrid.net."
        "url5505" "sendgrid.net."}))}}

   :SysrevEmailRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (ref :SysrevHostedZone)
     :RecordSets
     (fn-if
      :IsProd
      [(google-mx-recordset (ref :SysrevZoneApex))]
      (no-email-recordsets (ref :SysrevZoneApex)))}}

   :LoggingBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "Private"
     :BucketEncryption
     {:ServerSideEncryptionConfiguration
      [{:BucketKeyEnabled true
        :ServerSideEncryptionByDefault
        {:SSEAlgorithm "AES256"}}]}
     :LifecycleConfiguration
     {:Rules
      [{:ExpirationInDays 365
        :Status "Enabled"
        :Transitions
        [{:StorageClass "GLACIER"
          :TransitionInDays 1}]}]}
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}}}

   :LoggingBucketPolicy
   {:Type "AWS::S3::BucketPolicy"
    :Properties
    {:Bucket (ref :LoggingBucket)
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Principal {:Service "delivery.logs.amazonaws.com"}
        :Action "s3:PutObject"
        :Resource
        (join ""
              ["arn:aws:s3:::"
               (ref :LoggingBucket)
               "/AWSLogs/"
               account-id
               "/*"])
        :Condition
        {:StringEquals {:s3:x-amz-acl "bucket-owner-full-control"}}}
       {:Effect "Allow"
        :Principal {:Service "delivery.logs.amazonaws.com"}
        :Action "s3:GetBucketAcl"
        :Resource
        (join ""
              ["arn:aws:s3:::" (ref :LoggingBucket)])}]}}}

   :CodeBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "Private"
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}}}

   :CodeBucketFullAccessPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action "*"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :CodeBucket)])}
       {:Effect "Allow"
        :Action "*"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :CodeBucket) "/*"])}]}}}

   :CacheBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "Private"
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}}}

   :CacheBucketFullAccessPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action "*"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :CacheBucket)])}
       {:Effect "Allow"
        :Action "*"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :CacheBucket) "/*"])}]}}}

   :CacheBucketFullAccessRole
   {:Type "AWS::IAM::Role"
    :Properties
    {:AssumeRolePolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["sts:AssumeRole" "sts:TagSession"]
        :Effect "Allow"
        :Principal {:AWS (arn :GitHubActionsUser)}}]}
     :ManagedPolicyArns [(ref :CacheBucketFullAccessPolicy)]
     :MaxSessionDuration 7200
     :RoleName "CacheBucketFullAccessRole"}}

   :AdminAccessCloudFormationServiceRole
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

   :CloudFormationReadPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["cloudformation:DescribeStackEvents"
                 "cloudformation:DescribeStackResources"
                 "cloudformation:ListStacks"]
        :Effect "Allow"
        :Resource "*"}]}}}

   :CloudWatchReadPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["cloudwatch:DescribeAlarmHistory"
                 "cloudwatch:DescribeAlarms"
                 "cloudwatch:DescribeAlarmsForMetric"
                 "cloudwatch:DescribeAnomalyDetectors"
                 "cloudwatch:DescribeInsightRules"
                 "cloudwatch:GetDashboard"
                 "cloudwatch:GetInsightRuleReport"
                 "cloudwatch:GetMetricData"
                 "cloudwatch:GetMetricStatistics"
                 "cloudwatch:GetMetricStream"
                 "cloudwatch:GetMetricWidgetImage"
                 "cloudwatch:ListDashboards"
                 "cloudwatch:ListMetricStreams"
                 "cloudwatch:ListMetrics"
                 "cloudwatch:ListTagsForResource"
                 "logs:DescribeDestinations"
                 "logs:DescribeExportTasks"
                 "logs:DescribeLogGroups"
                 "logs:DescribeLogStreams"
                 "logs:DescribeMetricFilters"
                 "logs:DescribeQueries"
                 "logs:DescribeQueryDefinitions"
                 "logs:DescribeResourcePolicies"
                 "logs:DescribeSubscriptionFilters"
                 "logs:FilterLogEvents"
                 "logs:GetLogDelivery"
                 "logs:GetLogEvents"
                 "logs:GetLogGroupFields"
                 "logs:GetLogRecord"
                 "logs:GetQueryResults"
                 "logs:ListLogDeliveries"
                 "logs:ListTagsLogGroup"
                 "logs:StartQuery"
                 "logs:StopQuery"
                 "logs:TestMetricFilter"]
        :Effect "Allow"
        :Resource "*"}]}}}

   :ListBucketsPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action ["s3:ListAllMyBuckets"]
        :Effect "Allow"
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

   :CloudFrontOAI
   {:Type "AWS::CloudFront::CloudFrontOriginAccessIdentity"
    :Properties
    {:CloudFrontOriginAccessIdentityConfig
     {:Comment "CloudFront Origin Access Identity to allow access to S3 objects"}}}

   :DatapubBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "Private"
     :CorsConfiguration
     {:CorsRules
      [{:AllowedHeaders ["authorization"]
        :AllowedMethods ["GET" "HEAD"]
        :AllowedOrigins ["https://staging.sysrev.com"
                         "https://sysrev.com"
                         "https://www.sysrev.com"]
        :MaxAge 3600}]}
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}
     :Tags
     (tags :grant "thrive")}}

   :DatapubBucketPolicy
   {:Type "AWS::S3::BucketPolicy"
    :Properties
    {:Bucket (ref :DatapubBucket)
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action "s3:GetObject"
        :Effect "Allow"
        :Principal {:AWS (join "" ["arn:aws:iam::cloudfront:user/CloudFront Origin Access Identity " (ref :CloudFrontOAI)])}
        :Resource (join "" ["arn:aws:s3:::" (ref :DatapubBucket) "/*"])}]}}}

   :StaticSiteBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "PublicRead"
     :WebsiteConfiguration
     {:IndexDocument "index.html"
      :ErrorDocument "error.html"}}}

   :StaticSiteBucketPolicy
   {:Type "AWS::S3::BucketPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Sid "PublicReadForGetBucketObjects"
        :Effect "Allow"
        :Principal "*"
        :Action "s3:GetObject"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :StaticSiteBucket) "/*"])}]}
     :Bucket (ref :StaticSiteBucket)}}

   :StaticSiteCertificate
   {:Type "AWS::CertificateManager::Certificate"
    :Properties
    {:DomainName (join  "." ["static" (ref :SysrevZoneApex)])
     :DomainValidationOptions
     [{:DomainName (join  "." ["static" (ref :SysrevZoneApex)])
       :HostedZoneId (ref :SysrevHostedZone)}]
     :ValidationMethod "DNS"}}

   :StaticSiteDistribution
   {:Type "AWS::CloudFront::Distribution"
    :Properties
    {:DistributionConfig
     {:Aliases [(join  "." ["static" (ref :SysrevZoneApex)])]
      :DefaultCacheBehavior
      {:AllowedMethods ["GET" "HEAD"]
       :Compress true
       :ForwardedValues
       {:Cookies {:Forward "none"}
        :QueryString true}
       :TargetOriginId "StaticSiteBucketOrigin"
       :ViewerProtocolPolicy "redirect-to-https"}
      :Enabled true
      :HttpVersion "http2"
      :Logging
      {:Bucket (get-att :LoggingBucket "DomainName")
       :IncludeCookies false
       :Prefix (join "" ["cloudfront/static." (ref :SysrevZoneApex) "/"])}
      :Origins
      [{:CustomOriginConfig {:OriginProtocolPolicy "http-only"}
        :DomainName
        (join ""
              [(ref :StaticSiteBucket)
               ".s3-website-" region ".amazonaws.com"])
        :Id "StaticSiteBucketOrigin"}]
      :ViewerCertificate
      {:AcmCertificateArn (ref :StaticSiteCertificate)
       :SslSupportMethod "sni-only"}}}}

   :StaticSiteDistributionRecordSetGroup
   {:Type "AWS::Route53::RecordSetGroup"
    :Properties
    {:HostedZoneId (ref :SysrevHostedZone)
     :RecordSets
     [{:AliasTarget {:HostedZoneId cloudfront-hosted-zone-id
                     :DNSName (get-att :StaticSiteDistribution "DomainName")}
       :Name (join  "." ["static" (ref :SysrevZoneApex)])
       :Type "A"}
      {:AliasTarget {:HostedZoneId cloudfront-hosted-zone-id
                     :DNSName (get-att :StaticSiteDistribution "DomainName")}
       :Name (join  "." ["static" (ref :SysrevZoneApex)])
       :Type "AAAA"}]}}

   :StaticSiteFullAccessPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action "*"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :StaticSiteBucket)])}
       {:Effect "Allow"
        :Action "*"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :StaticSiteBucket) "/*"])}
       {:Effect "Allow"
        :Action "cloudfront:CreateInvalidation"
        :Resource
        (join "" ["arn:aws:cloudfront::" account-id ":distribution/" (ref :StaticSiteDistribution)])}]}}}

   :CredentialsGroup
   {:Type "AWS::IAM::Group"}

   :DevelopersGroup
   {:Type "AWS::IAM::Group"
    :Properties
    {:GroupName "sysrev-developers"
     :ManagedPolicyArns
     [(ref :CloudFormationReadPolicy)
      (ref :CloudWatchReadPolicy)
      (ref :ListBucketsPolicy)]}}

   :CredentialsUser
   {:Type "AWS::IAM::User"
    :Properties
    {:Groups [(ref :CredentialsGroup)]}}

   :GitHubActionsUser
   {:Type "AWS::IAM::User"
    :Properties
    {:Groups [(ref :CredentialsGroup)]
     :ManagedPolicyArns
     [(ref :AdminAccessCloudFormationServicePassRolePolicy)
      (ref :CloudFormationCreateUpdatePolicy)
      (ref :CodeBucketFullAccessPolicy)
      (ref :CacheBucketFullAccessPolicy)
      (ref :PackerBuildPolicy)
      (ref :StaticSiteFullAccessPolicy)]
     :UserName "github-actions"}}}

  :Outputs
  (prefixed-outputs
   "${AWS::StackName}-"
   {:AdminAccessCloudFormationServiceRoleArn [(arn :AdminAccessCloudFormationServiceRole)]
    :CacheBucket [(ref :CacheBucket)]
    :CloudFrontOAI [(ref :CloudFrontOAI)]
    :CodeBucket [(ref :CodeBucket)]
    :CredentialsGroupName [(ref :CredentialsGroup)]
    :DatapubBucket [(ref :DatapubBucket)]
    :DatapubHostedZoneId [(ref :DatapubHostedZone)]
    :DatapubZoneApex [(ref :DatapubZoneApex)]
    :DevelopersGroupArn [(arn :DevelopersGroup)]
    :InsilicaHostedZoneId [(fn-if :HasInsilicaZone (ref :InsilicaHostedZone) "nil")]
    :SysrevHostedZoneId [(ref :SysrevHostedZone)]
    :SysrevZoneApex [(ref :SysrevZoneApex)]}))
