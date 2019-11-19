(defproject sysrev-client "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.520"]

;;; clojure.spec
                 [orchestra "2018.12.06-2" #_ "2019.02.06-1"]

;;; JVM/CLJS deps
                 [org.clojure/tools.logging "0.5.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jul-to-slf4j "1.7.28"]
                 [org.clojure/test.check "0.10.0"]

;;; Client dependencies (CLJS)
                 [com.cognitect/transit-cljs "0.8.256"]
                 [reagent "0.8.1"]
                 [re-frame "0.10.9"]
                 [day8.re-frame/http-fx "0.1.6"]
                 [clj-commons/secretary "1.2.4"]
                 [kibu/pushy "0.3.8"]
                 [cljs-http "0.1.46"]
                 ;; only provides ext.js for stripe.js
                 [cljsjs/stripe "2.0-0"]
                 ;; stripe provided form components
                 [jborden/react-stripe-elements "5.0.1-0"]
                 [org.clojars.jeffwk/semantic-ui "2.4.0-0"]
                 [cljsjs/semantic-ui-react "0.83.0-0"]
                 [cljsjs/chartjs "2.7.3-0"]
                 [cljsjs/dropzone "5.5.0-1"]
                 [cljsjs/clipboard "1.6.1-1"]
                 [cljsjs/accounting "0.4.1-1"]
                 [cljsjs/moment "2.22.2-1"]
                 [jborden/croppie "2.6.3-1"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 ;; markdown converter
                 [cljsjs/showdown "1.8.6-0"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Djava.util.logging.config.file=resources/logging.properties"
             "-server"
             "-Xms600m" "-Xmx1200m"
             #_ "-XX:+UseParallelGC"
             "-XX:+TieredCompilation"
             #_ "-XX:+AggressiveOpts"
             #_ "-XX:TieredStopAtLevel=1"
             #_ "-XX:+UseConcMarkSweepGC"
             "-Xverify:none"]
  :source-paths ["src/cljs" "src/cljc"]
  :aliases {}
  :plugins [[lein-cljsbuild "1.1.7"]]
  :clean-targets ^{:protect false} ["target"
                                    #_ "resources/public/out-dev"
                                    #_ "resources/public/integration"
                                    "resources/public/out-production"]
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs" "src/cljc"]
     :figwheel {:on-jsload "sysrev.core/on-jsload"}
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
             #_ :nrepl-middleware
             #_ ["cider.nrepl/cider-middleware"
                 "cemerick.piggieback/wrap-cljs-repl"]
             :css-dirs ["resources/public/css/style.default.css"
                        "resources/public/css/style.dark.css"
                        "resources/public/semantic/default/semantic.min.css"
                        "resources/public/semantic/dark/semantic.min.css"
                        #_ "resources/public/css"]}
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:dev
             {:dependencies [[binaryage/devtools "0.9.10"]]}
             :figwheel
             {:jvm-opts ["-Xms300m" "-Xmx800m"]}})