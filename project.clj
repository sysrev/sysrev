(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.0"]

;;; Force versions of indirect dependencies
                 [com.fasterxml.jackson.core/jackson-databind "2.9.8"]
                 [cheshire "5.8.1"]
                 [commons-io/commons-io "2.6"]
                 [commons-codec "1.11"]

;;; Logging
                 [org.clojure/tools.logging "0.4.1"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]

;;; Libraries
                 [org.clojure/test.check "0.9.0"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [crypto-random "1.2.0"]
                 [me.raynes/fs "1.4.6"]

;;; Data formats
                 [org.clojure/data.json "0.2.6"]
                 [com.cognitect/transit-clj "0.8.313"]
                 [org.clojure/data.xml "0.2.0-alpha3"]
                 [org.clojure/data.zip "0.1.2"]
                 ;; (clojure-csv/2.0.1 because 2.0.2 changes parsing behavior)
                 [clojure-csv/clojure-csv "2.0.1"]

;;; Postgres
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.postgresql/postgresql "42.2.5"]
                 [joda-time "2.10.1"]
                 [clj-time "0.15.1" :exclusions [joda-time]]
                 [postgre-types "0.0.4"]
                 [hikari-cp "2.6.0"]
                 [clj-postgresql "0.7.0"
                  :exclusions [org.clojure/java.jdbc cheshire prismatic/schema]]
                 [honeysql "0.9.4"]
                 [nilenso/honeysql-postgres "0.2.5"]

;;; Cassandra
                 [cc.qbits/alia-all "4.3.0"]
                 [cc.qbits/hayt "4.0.2"]

;;; Web server
                 [javax.servlet/servlet-api "2.5"]
                 [http-kit "2.3.0"]
                 [manifold "0.1.8"]
                 [aleph "0.4.6"]
                 [ring "1.7.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-transit "0.1.6"]
                 [ring/ring-json "0.4.0" :exclusions [cheshire]]
                 [ring/ring-mock "0.3.2" :exclusions [cheshire]]
                 [compojure "1.6.1"]

;;; More libraries
                 [buddy "2.0.0"] ;; encryption/authentication
                 [clj-http "3.9.1"]
                 [com.velisco/clj-ftp "0.3.12"]
                 [com.draines/postal "2.0.3"] ;; email client
                 [amazonica "0.3.139"
                  :exclusions [com.taoensso/encore
                               com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
                               com.fasterxml.jackson.core/jackson-databind
                               org.slf4j/slf4j-api]]
                 ;; =1.23.0 because version conflict in latest
                 [com.google.api-client/google-api-client "1.23.0"]
                 [abengoa/clj-stripe "1.0.4"]
                 [environ "1.1.0"]
                 [bouncer "1.0.1"] ;; validation
                 [hickory "0.7.1"] ;; html parser
                 [kanwei/sitemap "0.3.1"] ;; sitemap alternative with clojure.spec fix
                 [org.clojure/core.memoize "0.7.1"]
                 [gravatar "1.1.1"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Djava.util.logging.config.file=resources/logging.properties"
             "-Xms800m"
             "-Xmx1500m"
             #_ "-XX:+UseParallelGC"
             "-XX:+TieredCompilation"
             "-XX:+AggressiveOpts"
             "-Xverify:none"]
  :source-paths ["src/clj" "src/cljc"]
  :aliases {"junit"
            ["with-profile" "+test,+test-all" "run"]
            "test-aws-dev-browser"
            ["with-profile" "+test,+test-browser,+test-aws-dev" "run"]
            "test-aws-prod-browser"
            ["with-profile" "+test,+test-browser,+test-aws-prod" "run"]
            "test-aws-dev-all"
            ["with-profile" "+test,+test-all,+test-aws-dev" "run"]}
  :clean-targets ^{:protect false} ["target"]
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:prod
             {:jvm-opts ["-Xms800m" "-Xmx1500m" "-server"]
              :resource-paths ["config/prod"]
              :main sysrev.web-main
              :aot [sysrev.web-main]}
             :test-browser
             {:resource-paths ["config/test"]
              :main sysrev.browser-test-main
              :aot [sysrev.browser-test-main]}
             :test-all
             {:resource-paths ["config/test"]
              :main sysrev.all-test-main
              :aot [sysrev.all-test-main]}
             :test-aws-dev
             {:resource-paths ["config/test-aws-dev"]}
             :test-aws-prod
             {:resource-paths ["config/test-aws-prod"]}
             :test-s3-dev
             {:resource-paths ["config/test-s3-dev"]}
             :dev
             {:jvm-opts ["-Xms1000m" "-Xmx2000m" "-server" #_ "-client"
                         #_ "-XX:TieredStopAtLevel=1" #_ "-XX:+UseConcMarkSweepGC"
                         #_ "-XX:+CMSClassUnloadingEnabled"]
              :resource-paths ["config/dev"]
              :source-paths ["src/clj" "src/cljc" "test/clj"]
              :test-paths ["test/clj"]
              :dependencies [[clj-webdriver "0.7.2"]
                             [org.seleniumhq.selenium/selenium-api "3.8.1"]
                             [org.seleniumhq.selenium/selenium-support "3.8.1"]
                             [org.seleniumhq.selenium/selenium-java "3.8.1"
                              :exclusions
                              [org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support]]
                             [org.seleniumhq.selenium/selenium-remote-driver "3.8.1"
                              :exclusions
                              [com.google.guava/guava]]
                             [org.seleniumhq.selenium/selenium-server "3.8.1"
                              :exclusions
                              [org.bouncycastle/bcpkix-jdk15on
                               org.bouncycastle/bcprov-jdk15on
                               org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support]]]}
             :repl
             {:dependencies [#_ [org.clojure/tools.nrepl "0.2.13"]
                             #_ [acyclic/squiggly-clojure "0.1.8"
                                 :exclusions [org.clojure/tools.reader]]]
              :plugins [#_ [cider/cider-nrepl "0.17.0"]
                        [lein-environ "1.1.0"]]}
             :dev-spark
             {:source-paths ["src/clj" "src/cljc" "src-spark" "test/clj"]
              :test-paths ["test/clj"]
              :resource-paths ["config/dev"]
              :dependencies
              [[yieldbot/flambo "0.8.2"
                :exclusions
                [com.google.guava/guava]]
               [org.apache.spark/spark-core_2.11 "2.2.1"]
               [org.apache.spark/spark-mllib_2.11 "2.2.1"]
               [org.apache.spark/spark-streaming_2.11 "2.2.1"]
               [org.apache.spark/spark-streaming-kafka-0-8_2.11 "2.2.1"]
               [org.apache.spark/spark-sql_2.11 "2.2.1"]
               [org.apache.spark/spark-hive_2.11 "2.2.1"]]
              :aot [sysrev.spark.core
                    sysrev.spark.similarity]}
             :test
             {:jvm-opts [#_ "-server" "-client"
                         "-XX:TieredStopAtLevel=1" #_ "-XX:+UseConcMarkSweepGC"]
              :resource-paths ["config/test" "resources/test"]
              :source-paths ["src/clj" "src/cljc" "test/clj"]
              :test-paths ["test/clj"]
              :dependencies []}})
