(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.1"]

;;; clojure.spec
                 [orchestra "2020.09.18-1"]

;;; Force versions of indirect dependencies
                 [com.fasterxml.jackson.core/jackson-databind "2.11.2"]
                 [cheshire "5.10.0"]
                 [commons-io/commons-io "2.7"]
                 [commons-codec "1.14"]
                 [org.apache.commons/commons-compress "1.20"]
                 [prismatic/schema "1.1.12"]

;;; Logging
                 [org.clojure/tools.logging "1.1.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jul-to-slf4j "1.7.30"]

;;; Libraries
                 [org.clojure/test.check "1.1.0"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/math.combinatorics "0.1.6"]
                 [crypto-random "1.2.0"]
                 [me.raynes/fs "1.4.6"]
                 [eftest "0.5.9"]

;;; Data formats
                 [org.clojure/data.json "1.0.0"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [org.clojure/data.xml "0.2.0-alpha3"]
                 [org.clojure/data.zip "1.0.0"]
                 ;; (clojure-csv/2.0.1 because 2.0.2 changes parsing behavior)
                 [clojure-csv/clojure-csv "2.0.1"]
;;; GraphQL
                 [com.walmartlabs/lacinia "0.37.0"]
                 [vincit/venia "0.2.5"]

;;; Postgres
                 [org.clojure/java.jdbc "0.7.11"]
                 [org.postgresql/postgresql "42.2.16"]
                 [joda-time "2.10.6"]
                 [clj-time "0.15.2" :exclusions [joda-time]]
                 [postgre-types "0.0.4"]
                 [hikari-cp "2.13.0"]
                 [clj-postgresql "0.7.0"
                  :exclusions [org.clojure/java.jdbc cheshire prismatic/schema]]
                 [honeysql "0.9.8"]
                 [nilenso/honeysql-postgres "0.2.6"]

;;; Cassandra
                 [cc.qbits/alia-all "4.3.3"]
                 [cc.qbits/hayt "4.1.0"]

;;; Web server
                 [javax.servlet/servlet-api "2.5"]
                 [manifold "0.1.8"]
                 [aleph "0.4.6"]
                 [ring "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-transit "0.1.6"]
                 [ring/ring-json "0.5.0" :exclusions [cheshire]]
                 [ring/ring-mock "0.4.0" :exclusions [cheshire]]
                 [compojure "1.6.2"]
;;; profiling
                 [criterium "0.4.6"]

;;; More libraries
                 [buddy "2.0.0"] ;; encryption/authentication
                 [clj-http "3.10.2"]
                 [com.velisco/clj-ftp "0.3.12"]
                 [com.draines/postal "2.0.3"] ;; email client
                 [amazonica "0.3.152"
                  :exclusions [com.taoensso/encore
                               com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
                               com.fasterxml.jackson.core/jackson-databind
                               org.slf4j/slf4j-api]]
                 ;; =1.23.0 because version conflict in latest (1.30.2 breaks selenium)
                 [com.google.api-client/google-api-client "1.23.0"]
                 [environ "1.2.0"]
                 [bouncer "1.0.1"] ;; validation
                 [hickory "0.7.1"] ;; html parser
                 [kanwei/sitemap "0.3.1"] ;; sitemap alternative with clojure.spec fix
                 [org.clojure/core.memoize "0.7.2"]
                 [gravatar "1.1.1"]
                 [medley "1.3.0"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Djava.util.logging.config.file=resources/logging.properties"
             "-server"
             ;; silence reflection warning in JVM >=10:
             ;; "--add-opens" "java.xml/com.sun.xml.internal.stream=ALL-UNNAMED"
             "-Xms500m" "-Xmx1000m"
             "-XX:+TieredCompilation"
             #_ "-XX:TieredStopAtLevel=1"
             #_ "-XX:+AggressiveOpts"
             #_ "-Xverify:none"
             #_ "-XX:+UseParallelGC"
             #_ "-XX:+UnlockExperimentalVMOptions"
             #_ "-XX:+UseZGC"]
  :source-paths ["src/clj" "src/cljc"]
  :aliases {"run-tests"              ["with-profile" "+test-config" "eftest"]
            "jenkins"                ["with-profile" "+jenkins" "eftest"]
            "junit"                  ["with-profile" "+test,+test-all" "run"]
            "test-aws-dev-browser"   ["with-profile" "+test,+test-browser,+test-aws-dev" "run"]
            "test-aws-prod-browser"  ["with-profile" "+test,+test-browser,+test-aws-prod" "run"]
            "test-aws-dev-all"       ["with-profile" "+test,+test-all,+test-aws-dev" "run"]}
  :clean-targets ^{:protect false} ["target"]
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:prod           {:resource-paths ["config/prod"]
                              :main sysrev.web-main
                              :aot [sysrev.web-main]}
             :test-browser   {:resource-paths ["config/test"]
                              :main sysrev.browser-test-main}
             :test-all       {:resource-paths ["config/test"]
                              :main sysrev.all-test-main}
             :test-aws-dev   {:resource-paths ["config/test-aws-dev"]}
             :test-aws-prod  {:resource-paths ["config/test-aws-prod"]}
             :test-s3-dev    {:resource-paths ["config/test-s3-dev"]}
             :dev            {:jvm-opts ["-Xmx1200m"]
                              :resource-paths ["config/dev"]
                              :source-paths ["src/clj" "src/cljc" "test/clj"]
                              :test-paths ["test/clj"]
                              :dependencies
                              [[clj-webdriver "0.7.2"]
                               [org.seleniumhq.selenium/selenium-api "3.8.1"]
                               [org.seleniumhq.selenium/selenium-support "3.8.1"]
                               [org.seleniumhq.selenium/selenium-java "3.8.1"
                                :exclusions [org.seleniumhq.selenium/selenium-api
                                             org.seleniumhq.selenium/selenium-support]]
                               [org.seleniumhq.selenium/selenium-remote-driver "3.8.1"
                                :exclusions [com.google.guava/guava]]
                               [org.seleniumhq.selenium/selenium-server "3.8.1"
                                :exclusions [org.bouncycastle/bcpkix-jdk15on
                                             org.bouncycastle/bcprov-jdk15on
                                             org.seleniumhq.selenium/selenium-api
                                             org.seleniumhq.selenium/selenium-support]]]
                              :plugins [[lein-eftest "0.5.9"]]}
             :repl           {:dependencies []
                              :plugins [[lein-environ "1.2.0"]]}
             :test           {:jvm-opts ["-Xmx1000m"]
                              :resource-paths ["config/test" "resources/test"]
                              :source-paths ["src/clj" "src/cljc" "test/clj"]
                              :test-paths ["test/clj"]}
             ;; :test-config    {:eftest {}}
             :jenkins        {:eftest {:thread-count 4
                                       :report eftest.report.junit/report
                                       :report-to-file "target/junit.xml"}}})
