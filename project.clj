(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [;; Clojure (JVM) libraries
                 ;;
                 [org.clojure/clojure "1.9.0-beta2"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 ;; Data formats
                 [org.clojure/data.json "0.2.6"]
                 [me.raynes/fs "1.4.6"]
                 #_ [org.clojure/data.codec "0.1.0"]
                 [org.clojure/data.xml "0.2.0-alpha3"]
                 [org.clojure/data.zip "0.1.2"]
                 ;; enforce jackson version to easier catch dependency conflicts
                 [com.fasterxml.jackson.core/jackson-databind "2.8.7"]
                 ;; Logging
                 [org.clojure/tools.logging "0.4.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]
                 [log4j/log4j "1.2.17"]
                 ;; clojure-csv/2.0.1 because 2.0.2 changes parsing behavior
                 [clojure-csv/clojure-csv "2.0.1"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 ;; Database
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.postgresql/postgresql "42.1.4"]
                 [joda-time "2.9.9"]
                 [clj-time "0.14.0"
                  :exclusions [joda-time]]
                 [postgre-types "0.0.4"]
                 [hikari-cp "1.8.1"]
                 [clj-postgresql "0.7.0"
                  :exclusions [org.clojure/java.jdbc
                               cheshire]]
                 [honeysql "0.9.1"]
                 [nilenso/honeysql-postgres "0.2.3"]
                 ;; Web server
                 [compojure "1.6.0"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring "1.6.2"]
                 [ring/ring-defaults "0.3.1"]
                 [ring-transit "0.1.6"]
                 [ring/ring-json "0.4.0" :exclusions [cheshire]]
                 [http-kit "2.2.0"]
                 ;; Encryption / Authentication
                 [buddy "1.3.0"]
                 ;; Web client
                 [clj-http "3.7.0"]
                 [crypto-random "1.2.0"]
                 ;; Email
                 [com.draines/postal "2.0.2"]
                 ;; Amazon
                 [amazonica "0.3.93"
                  :exclusions [com.taoensso/encore
                               com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
                               com.fasterxml.jackson.core/jackson-databind
                               org.slf4j/slf4j-api]]
                 [commons-io/commons-io "2.5"]
                 ;; ClojureScript libraries
                 [reagent "0.7.0"]
                 [re-frame "0.10.2"]
                 [day8.re-frame/http-fx "0.1.4"]
                 [secretary "1.2.3"]
                 [kibu/pushy "0.3.7"]
                 ;; [cljs-ajax "0.6.0"]
                 [cljs-http "0.1.43"]
                 ;; [cljsjs/jquery "2.2.4-0"]
                 [cljsjs/jquery "3.2.1-0"]
                 ;; [cljsjs/semantic-ui "2.2.4-0"]
                 [org.clojars.jeffwk/semantic-ui "2.2.13-0"]
                 [camel-snake-kebab "0.4.0"]
                 [cljsjs/chartjs "2.6.0-0"]
                 [cljsjs/dropzone "4.3.0-0"]
                 [org.clojure/test.check "0.9.0"]
                 [cljsjs/clipboard "1.6.1-1"]
                 [com.andrewmcveigh/cljs-time "0.5.1"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Xms500m"
             "-Xmx1000m"
             "-server"
             "-XX:+TieredCompilation"
             "-XX:+AggressiveOpts"
             "-XX:+UseParNewGC"
             "-XX:+UseConcMarkSweepGC"
             "-XX:+CMSConcurrentMTEnabled"]
  :source-paths ["src/clj" "src/cljc"]
  :aliases {"junit"
            ["with-profile" "+test,+test-all" "run"]
            "test-aws-dev-browser"
            ["with-profile" "+test,+test-browser,+test-aws-dev" "run"]
            "test-aws-prod-browser"
            ["with-profile" "+test,+test-browser,+test-aws-prod" "run"]
            "test-aws-dev-all"
            ["with-profile" "+test,+test-all,+test-aws-dev" "run"]}
  :plugins [[lein-cljsbuild "1.1.7"]]
  :clean-targets ^{:protect false}
  ["target"
   "resources/public/out-dev"
   "resources/public/out-production"
   "resources/public/integration"]
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs" "src/cljc"]
     :figwheel {:on-jsload "sysrev.core/mount-root"}
     :compiler {:main "sysrev.user"
                :output-to "resources/public/out-dev/sysrev.js"
                :output-dir "resources/public/out-dev"
                :asset-path "/out"
                :optimizations :none
                :pretty-print true
                :source-map true
                :source-map-timestamp true
                :preloads [devtools.preload]
                :external-config {:devtools/config {:features-to-install :all}}}}
    {:id "production"
     :source-paths ["src/cljs" "src/cljc"]
     :compiler {:main "sysrev.core"
                :output-to "resources/public/out-production/sysrev.js"
                :output-dir "resources/public/out-production"
                :asset-path "/out"
                :closure-defines {goog.DEBUG false}
                :optimizations :advanced
                :pretty-print false
                :source-map "resources/public/out-production/sysrev.js.map"
                :source-map-timestamp true}}]}
  :figwheel {:nrepl-port 7888
             :server-port 3449
             ;; these should work with both Cider and Cursive
             :nrepl-middleware ["cider.nrepl/cider-middleware"
                                "refactor-nrepl.middleware/wrap-refactor"
                                "cemerick.piggieback/wrap-cljs-repl"]
             :css-dirs ["resources/public/css"
                        "resources/public/semantic/default/semantic.min.css"
                        "resources/public/semantic/dark/semantic.min.css"]}
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :eastwood {:exclude-linters [:unlimited-use :unused-ret-vals :constant-test]
             :config-files ["eastwood.clj"]}
  :profiles {:prod
             {:jvm-opts ["-Xms800m" "-Xmx1500m"]
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
             {:jvm-opts ["-Djava.util.logging.config.file=logging.properties"
                         "-Xms3000m" "-Xmx3000m"]
              :resource-paths ["config/dev"]
              :source-paths ["src/clj" "src/cljc" "test/clj"]
              :test-paths ["test/clj"]
              :dependencies [[org.clojure/test.check "0.9.0"]
                             [binaryage/devtools "0.9.7"]
                             [clj-webdriver "0.7.2"]
                             [org.seleniumhq.selenium/selenium-api "3.4.0"]
                             [org.seleniumhq.selenium/selenium-support "3.4.0"]
                             [org.seleniumhq.selenium/selenium-java "3.4.0"
                              :exclusions
                              [org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support]]
                             [org.seleniumhq.selenium/selenium-remote-driver "3.4.0"
                              :exclusions
                              [com.google.guava/guava]]
                             [org.seleniumhq.selenium/selenium-server "3.4.0"
                              :exclusions
                              [org.bouncycastle/bcpkix-jdk15on
                               org.bouncycastle/bcprov-jdk15on
                               org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support]]
                             [com.codeborne/phantomjsdriver "1.4.3"]]}
             :repl
             {:dependencies [[figwheel-sidecar "0.5.14"]
                             [org.clojure/tools.nrepl "0.2.13"]
                             [com.cemerick/piggieback "0.2.2"]
                             [acyclic/squiggly-clojure "0.1.8"
                              :exclusions [org.clojure/tools.reader]]]
              :plugins [[lein-figwheel "0.5.14"]
                        [cider/cider-nrepl "0.15.1"]
                        [refactor-nrepl "2.3.1"]
                        [lein-environ "1.1.0"]]
              :env {:squiggly
                    {:checkers [:eastwood #_ :kibit]
                     :eastwood-exclude-linters
                     [:unlimited-use :unused-ret-vals :constant-test]
                     :eastwood-options {:config-files ["eastwood.clj"]}}}}
             :figwheel
             {:jvm-opts ["-Djava.util.logging.config.file=logging.properties"
                         "-Xms250m" "-Xmx500m"]}
             :dev-spark
             {:source-paths ["src/clj" "src/cljc" "src-spark" "test/clj"]
              :test-paths ["test/clj"]
              :resource-paths ["config/dev"]
              :dependencies
              [[yieldbot/flambo "0.8.2"
                :exclusions
                [com.google.guava/guava]]
               [org.apache.spark/spark-core_2.11 "2.2.0"]
               [org.apache.spark/spark-mllib_2.11 "2.2.0"]
               [org.apache.spark/spark-streaming_2.11 "2.2.0"]
               [org.apache.spark/spark-streaming-kafka-0-8_2.11 "2.2.0"]
               [org.apache.spark/spark-sql_2.11 "2.2.0"]
               [org.apache.spark/spark-hive_2.11 "2.2.0"]]
              :aot :all}
             :test
             {:jvm-opts ["-Dlog4j.configuration=log4j.properties.test"
                         "-Djava.util.logging.config.file=logging.properties"]
              :resource-paths ["config/test"]
              :source-paths ["src/clj" "src/cljc" "test/clj"]
              :test-paths ["test/clj"]
              :dependencies [[org.clojure/test.check "0.9.0"]
                             [clj-webdriver "0.7.2"]
                             [org.seleniumhq.selenium/selenium-api "3.4.0"]
                             [org.seleniumhq.selenium/selenium-support "3.4.0"]
                             [org.seleniumhq.selenium/selenium-java "3.4.0"
                              :exclusions
                              [org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support]]
                             [org.seleniumhq.selenium/selenium-remote-driver "3.4.0"
                              :exclusions
                              [com.google.guava/guava]]
                             [org.seleniumhq.selenium/selenium-server "3.4.0"
                              :exclusions
                              [org.bouncycastle/bcpkix-jdk15on
                               org.bouncycastle/bcprov-jdk15on
                               org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support]]
                             [com.codeborne/phantomjsdriver "1.4.3"]]}
             :autotest
             {:dependencies {}}})
