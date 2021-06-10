(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.3"]

;;; clojure.spec
                 [orchestra "2021.01.01-1"]

;;; Force versions of indirect dependencies
                 [com.fasterxml.jackson.core/jackson-databind "2.12.3"]
                 [cheshire "5.10.0"]
                 [commons-io/commons-io "2.8.0"]
                 [commons-codec "1.15"]
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
                 [crypto-random "1.2.1"]
                 [me.raynes/fs "1.4.6"]
                 [eftest "0.5.9"]

;;; Data formats
                 [org.clojure/data.json "2.2.2"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [org.clojure/data.xml "0.2.0-alpha3"]
                 [org.clojure/data.zip "1.0.0"]
                 ;; (clojure-csv/2.0.1 because 2.0.2 changes parsing behavior)
                 [clojure-csv/clojure-csv "2.0.1"]
;;; GraphQL
                 [com.walmartlabs/lacinia "0.37.0"]
                 [vincit/venia "0.2.5"]

;;; Postgres
                 [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.8.7"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.2.20"]
                 [joda-time "2.10.10"]
                 [clj-time "0.15.2" :exclusions [joda-time]]
                 [postgre-types "0.0.4"]
                 [hikari-cp "2.13.0"]
                 [clj-postgresql "0.7.0"
                  :exclusions [org.clojure/java.jdbc cheshire prismatic/schema]]
                 [honeysql "0.9.8"]
                 [nilenso/honeysql-postgres "0.2.6"]
                 [org.clojure/java.data "1.0.86"] ;; next.jdbc needs latest
                 [com.github.seancorfield/next.jdbc "1.2.659"]
                 [org.flywaydb/flyway-core "7.9.1"]

;;; Web server
                 [javax.servlet/servlet-api "2.5"]
                 [manifold "0.1.8"]
                 [aleph "0.4.6"]
                 [com.taoensso/sente "1.16.2"]
                 [ring "1.9.3"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-transit "0.1.6"]
                 [ring/ring-json "0.5.1" :exclusions [cheshire]]
                 [ring/ring-mock "0.4.0" :exclusions [cheshire]]
                 [compojure "1.6.2"]
;;; profiling
                 [criterium "0.4.6"]

;;; More libraries
                 [buddy "2.0.0"] ;; encryption/authentication
                 [clj-http "3.12.1"]
                 [com.stuartsierra/component "1.0.0"]
                 [com.velisco/clj-ftp "0.3.12"]
                 [com.draines/postal "2.0.4"] ;; email client
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
                 [org.clojure/core.async "1.3.610"]
                 [org.clojure/core.memoize "0.7.2"]
                 [gravatar "1.1.1"]
                 [medley "1.3.0"]
                 [lambdaisland/regal "0.0.97"] ;; portable regexes
                 [clojurewerkz/quartzite "2.1.0"]]
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
  :source-paths ["src/clj" "src/cljc"
                 "components/flyway/src"
                 "components/notification/src"
                 "components/user/src"]
  :aliases {"build-prod"             ["with-profile" "+postgres,+prod" "uberjar"]
            "run-tests"              ["with-profile" "+postgres-embedded,+test-config" "eftest"]
            "jenkins"                ["with-profile" "+postgres-embedded,+jenkins" "eftest"]
            "junit"                  ["with-profile" "+postgres-embedded,+test,+test-all" "run"]
            "test-aws-dev-browser"   ["with-profile" "+postgres,+test-remote,+test-browser,+test-aws-dev" "run"]
            "test-aws-prod-browser"  ["with-profile" "+postgres,+test-remote,+test-browser,+test-aws-prod" "run"]
            "test-aws-dev-all"       ["with-profile" "+postgres,+test-remote,+test-all,+test-aws-dev" "run"]}
  :clean-targets ^{:protect false} ["target"]
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:prod           {:resource-paths ["config/prod" "flyway.conf"]
                              :main sysrev.web-main
                              :aot [sysrev.web-main]}
             :test-browser   {:resource-paths ["config/test"]
                              :main sysrev.browser-test-main}
             :test-all       {:resource-paths ["config/test"]
                              :main sysrev.all-test-main}
             :test-aws-dev   {:resource-paths ["config/test-aws-dev"]}
             :test-aws-prod  {:resource-paths ["config/test-aws-prod"]}
             :test-s3-dev    {:resource-paths ["config/test-s3-dev"]}
             :dev            {:jvm-opts ["-Xmx1200m"
                                         "-Djdk.attach.allowAttachSelf=true"]
                              :resource-paths ["config/dev"
                                               "components/fixtures/resources"]
                              :source-paths ["src/clj" "src/cljc" "test/clj"
                                             "components/fixtures/src"]
                              :test-paths ["test/clj"
                                           "components/notification/test"
                                           "components/user/test"]
                              :dependencies
                              [[cider/cider-nrepl "0.26.0"]
                               [clj-webdriver "0.7.2"]
                               [com.clojure-goes-fast/clj-async-profiler "0.5.0"]
                               [etaoin "0.4.1"]
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
                                             org.seleniumhq.selenium/selenium-support]]
                               [prestancedesign/get-port "0.1.1"]]
                              :plugins [[lein-eftest "0.5.9"]]}
             :postgres       {:resource-paths ["components/postgres/resources"]
                              :source-paths ["components/postgres/src"]}
             :postgres-embedded {:dependencies
                                 [[com.opentable.components/otj-pg-embedded "0.13.3"]
                                  [io.zonky.test.postgres/embedded-postgres-binaries-darwin-amd64 "12.6.0"]
                                  [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64 "12.6.0"]
                                  [prestancedesign/get-port "0.1.1"]]
                                 :resource-paths ["components/postgres-in-mem/resources"]
                                 :source-paths ["components/postgres-in-mem/src"]}
             :repl           {:plugins [[lein-environ "1.2.0"]]}
             :test           {:dependencies
                              [[prestancedesign/get-port "0.1.1"]]
                              :jvm-opts ["-Xmx1000m"]
                              :resource-paths ["config/test" "resources/test"
                                               "components/fixtures/resources"]
                              :source-paths ["src/clj" "src/cljc" "test/clj"
                                             "components/fixtures/src"]
                              :test-paths ["test/clj"
                                           "components/notification/test"
                                           "components/user/test"]}
             :test-remote    {:dependencies
                              [[prestancedesign/get-port "0.1.1"]]
                              :jvm-opts ["-Xmx1000m"]
                              :resource-paths ["config/test" "resources/test"]
                              :source-paths ["src/clj" "src/cljc" "test/clj"]
                              :test-paths ["test/clj"]}
             :jenkins        {:eftest {:report eftest.report.junit/report
                                       :report-to-file "target/junit.xml"
                                       :thread-count 4}}})
