(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [;;;
                 ;;; Clojure
                 ;;;
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.238"]

                 ;;;
                 ;;; Force versions of indirect dependencies
                 ;;;
                 [com.fasterxml.jackson.core/jackson-databind "2.6.5"]
                 [cheshire "5.5.0"]
                 [commons-io/commons-io "2.6"]
                 [commons-codec "1.10"]

                 ;;;
                 ;;; Logging
                 ;;;
                 [org.clojure/tools.logging "0.4.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]

                 ;;;
                 ;;; Clojure (JVM) libraries
                 ;;;
                 [org.clojure/test.check "0.9.0"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [crypto-random "1.2.0"]
                 [me.raynes/fs "1.4.6"]
                 ;;; Data formats
                 [org.clojure/data.json "0.2.6"]
                 [com.cognitect/transit-clj "0.8.309"]
                 [org.clojure/data.xml "0.2.0-alpha3"]
                 [org.clojure/data.zip "0.1.2"]
                 ;; (clojure-csv/2.0.1 because 2.0.2 changes parsing behavior)
                 [clojure-csv/clojure-csv "2.0.1"]
                 ;;; Database
                 [org.clojure/java.jdbc "0.7.5"]
                 [org.postgresql/postgresql "42.2.2"]
                 [joda-time "2.9.9"]
                 [clj-time "0.14.2" :exclusions [joda-time]]
                 [postgre-types "0.0.4"]
                 [hikari-cp "2.0.1"]
                 [clj-postgresql "0.7.0"
                  :exclusions [org.clojure/java.jdbc
                               cheshire]]
                 [honeysql "0.9.2"]
                 [nilenso/honeysql-postgres "0.2.3"]
                 ;;; Web server
                 [javax.servlet/servlet-api "2.5"]
                 [http-kit "2.3.0"]
                 [ring "1.6.3"]
                 [ring/ring-defaults "0.3.1"]
                 [ring-transit "0.1.6"]
                 [ring/ring-json "0.4.0" :exclusions [cheshire]]
                 [ring/ring-mock "0.3.2" :exclusions [cheshire]]
                 [compojure "1.6.0"]
                 ;;; Encryption / Authentication
                 [buddy "2.0.0"]
                 ;;; Web client
                 [clj-http "3.8.0"]
                 ;;; Email
                 [com.draines/postal "2.0.2"]
                 ;;; Amazon
                 [amazonica "0.3.121"
                  :exclusions [com.taoensso/encore
                               com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
                               com.fasterxml.jackson.core/jackson-databind
                               org.slf4j/slf4j-api]]

                 ;;; Stripe
                 [abengoa/clj-stripe "1.0.4"]

                 ;;; environ
                 [environ "1.1.0"]

                 ;;; caching
                 [org.clojure/core.memoize "0.7.1"]

                 ;;;
                 ;;; ClojureScript libraries
                 ;;;
                 [com.cognitect/transit-cljs "0.8.256"]
                 [reagent "0.7.0"]
                 [re-frame "0.10.5"]
                 [day8.re-frame/http-fx "0.1.6"]
                 [secretary "1.2.3"]
                 [kibu/pushy "0.3.8"]
                 [cljs-http "0.1.45"]
                 [cljsjs/jquery "3.2.1-0"]
                 ;; only provides ext.js for stripe.js
                 [cljsjs/stripe "2.0-0"]
                 ;; stripe provided form components
                 [cljsjs/react-stripe-elements "1.4.1-1"]
                 #_ [cljsjs/semantic-ui "2.2.4-0"]
                 ;; custom build of cljsjs/semantic-ui to use latest version
                 ;; built from ./cljsjs/semantic-ui
                 [org.clojars.jeffwk/semantic-ui "2.2.13-0"]
                 [cljsjs/semantic-ui-react "0.78.2-0"]
                 [cljsjs/chartjs "2.6.0-0"]
                 [cljsjs/dropzone "4.3.0-0"]
                 [cljsjs/clipboard "1.6.1-1"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 ;; validation library
                 [bouncer "1.0.1"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Djava.util.logging.config.file=resources/logging.properties"
             "-Xms800m"
             "-Xmx1500m"
             "-server"
             "-XX:+TieredCompilation"
             "-XX:+AggressiveOpts"
             #_ "-XX:+UseParNewGC"
             #_ "-XX:+UseConcMarkSweepGC"
             #_ "-XX:+CMSConcurrentMTEnabled"]
  :source-paths ["src/clj" "src/cljc"]
  :aliases {"junit"
            ["with-profile" "+test,+test-all" "run"]
            "test-aws-dev-browser"
            ["with-profile" "+test,+test-browser,+test-aws-dev" "run"]
            "test-aws-prod-browser"
            ["with-profile" "+test,+test-browser,+test-aws-prod" "run"]
            "test-aws-dev-all"
            ["with-profile" "+test,+test-all,+test-aws-dev" "run"]
            "browser-test"
            ["do"
             ["cljsbuild" "once" "production"]
             ["test"]]}
  :plugins [[lein-cljsbuild "1.1.7"]]
  :clean-targets ^{:protect false}
  ["target"
   "resources/public/out-dev"
   "resources/public/integration"
   #_ "resources/public/out-production"]
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
                :external-config {:devtools/config {:features-to-install :all}}
                :npm-deps false}}
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
                :source-map-timestamp true
                :npm-deps false}}]}
  :figwheel {:nrepl-port 7888
             :server-port 3449
             ;; these should work with both Cider and Cursive
             :nrepl-middleware ["cider.nrepl/cider-middleware"
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
             {:jvm-opts ["-Xms800m" "-Xmx1500m"]
              :resource-paths ["config/dev"]
              :source-paths ["src/clj" "src/cljc" "test/clj"]
              :test-paths ["test/clj"]
              :dependencies [[binaryage/devtools "0.9.10"]
                             [clj-webdriver "0.7.2"]
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
             :dev-jvm
             {:jvm-opts ["-Xms1000m" "-Xmx2000m"]}
             :repl
             {:dependencies [[figwheel-sidecar "0.5.16"]
                             [org.clojure/tools.nrepl "0.2.13"]
                             [com.cemerick/piggieback "0.2.2"]
                             [acyclic/squiggly-clojure "0.1.8"
                              :exclusions [org.clojure/tools.reader]]]
              :plugins [[lein-figwheel "0.5.16"]
                        [cider/cider-nrepl "0.17.0"]
                        [lein-environ "1.1.0"]]}
             :figwheel
             {:jvm-opts ["-Xms300m" "-Xmx600m"]}
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
             {:resource-paths ["config/test" "resources/test"]
              :source-paths ["src/clj" "src/cljc" "test/clj"]
              :test-paths ["test/clj"]
              :dependencies []}})
