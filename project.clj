(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [;; Clojure (JVM) libraries
                 ;;
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.456"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 ;;[org.clojure/clojurescript "1.9.93"]
                 [org.clojure/core.async "0.2.395"]
                 ;; Data formats
                 [org.clojure/data.json "0.2.6"]
                 ;; [cheshire "5.6.3"]
                 [me.raynes/fs "1.4.6"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]
                 [clojure-csv/clojure-csv "2.0.1"]
                 ;; REPL
                 [org.clojure/tools.nrepl "0.2.12"]
                 [com.cemerick/piggieback "0.2.1"]
                 [figwheel-sidecar "0.5.8"]
                 ;; Database
                 [org.postgresql/postgresql "9.4.1212"]
                 [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2"]
                 [postgre-types "0.0.4"]
                 [clj-postgresql "0.4.0"
                  :exclusions
                  [clj-time
                   joda-time
                   com.fasterxml.jackson.dataformat/jackson-dataformat-smile
                   com.fasterxml.jackson.core/jackson-core
                   commons-codec
                   cheshire]]
                 [joda-time "2.9.7"]
                 [clj-time "0.13.0"
                  :exclusions [joda-time]]
                 [honeysql "0.8.2"]
                 [nilenso/honeysql-postgres "0.2.2"]
                 ;; Web server
                 [compojure "1.5.2"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring "1.5.1"]
                 [ring/ring-defaults "0.2.2"]
                 [ring/ring-json "0.4.0"]
                 [http-kit "2.2.0"]
                 ;; Encryption / Authentication
                 [buddy "1.1.0"]
                 ;; Web client
                 [clj-http "3.4.1"]
                 [crypto-random "1.2.0"]
                 ;; Email
                 [com.draines/postal "2.0.2"]
                 ;; Browser automation
                 [clj-webdriver "0.7.2"]
                 ;; Project config file support
                 [yogthos/config "0.8"]
                 ;; Logging
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.22"]
                 [log4j/log4j "1.2.17"]
                 ;; ClojureScript libraries
                 [reagent "0.6.0"]
                 [secretary "1.2.3"]
                 [kibu/pushy "0.3.6"]
                 [cljs-ajax "0.5.8"]
                 [cljs-http "0.1.42"]
                 [cljsjs/jquery "2.2.4-0"]
                 [cljsjs/semantic-ui "2.2.4-0"]
                 [com.andrewmcveigh/cljs-time "0.4.0"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Xms600m"
             "-Xmx1000m"
             "-server"
             "-XX:+TieredCompilation"
             "-XX:+AggressiveOpts"]
  :source-paths ["src/clj" "src/cljc" "src/scripts"]
  :plugins [[lein-cljsbuild "1.1.5"]
            [lein-bower "0.5.2"]
            [lein-ring "0.10.0"]
            [cider/cider-nrepl "0.14.0"]
            [refactor-nrepl "2.2.0"]
            [lein-figwheel "0.5.8"]]
  :clean-targets ^{:protect false} ["target"]
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs" "src/cljc" "src/scripts"]
     :figwheel true
     :compiler {:main "sysrev.user"
                :output-to "resources/public/out-dev/sysrev.js"
                :output-dir "resources/public/out-dev"
                :asset-path "/out"
                ;; :preloads      [devtools.preload]
                :optimizations :none
                :pretty-print true
                :source-map true
                :source-map-timestamp true}}
    {:id "production"
     :source-paths ["src/cljs"]
     :compiler {:main "sysrev.main"
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
             :css-dirs ["resources/public/css"]}
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:prod
             {:resource-paths ["config/prod"]
              :main sysrev.web-main
              :aot [sysrev.web-main]}
             :dev
             {:resource-paths ["config/dev"]}
             :dev-spark
             {:source-paths ["src/clj" "src-spark" "src/scripts"]
              :resource-paths ["config/dev"]
              :dependencies
              [[yieldbot/flambo "0.8.0"
                :exclusions
                [com.google.guava/guava]]
               [org.apache.spark/spark-core_2.11 "2.1.0"]
               [org.apache.spark/spark-mllib_2.11 "2.1.0"]
               [org.apache.spark/spark-streaming_2.11 "2.1.0"]
               [org.apache.spark/spark-streaming-kafka-0-8_2.11 "2.1.0"]
               [org.apache.spark/spark-sql_2.11 "2.1.0"]
               [org.apache.spark/spark-hive_2.11 "2.1.0"]]
              :aot :all}
             :test
             {:resource-paths ["config/test"]
              :source-paths ["src/clj" "src/cljs" "test/clj"]
              :test-paths ["test/clj"]}
             :autotest
             {:dependencies {}}})
