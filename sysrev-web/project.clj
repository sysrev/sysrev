(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [;; Clojure (JVM) libraries
                 ;;
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 ;;[org.clojure/clojurescript "1.9.93"]
                 [org.clojure/core.async "0.2.391"]
                 [org.clojure/data.json "0.2.6"]
                 ;; REPL
                 [org.clojure/tools.nrepl "0.2.12"]
                 [com.cemerick/piggieback "0.2.1"]
                 [figwheel-sidecar "0.5.7"]
                 ;; Database
                 [org.postgresql/postgresql "9.4.1210"]
                 [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2"]
                 [postgre-types "0.0.4"]
                 [clj-postgresql "0.4.0"
                  :exclusions
                  [joda-time
                   com.fasterxml.jackson.dataformat/jackson-dataformat-smile
                   com.fasterxml.jackson.core/jackson-core
                   commons-codec
                   clj-time
                   cheshire]]
                 [honeysql "0.8.0"]
                 ;; Web server
                 [compojure "1.5.1"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring "1.5.0"]
                 [ring/ring-defaults "0.2.1"]
                 [http-kit "2.2.0"]
                 ;; Encryption / Authentication
                 [crypto-random "1.2.0"]
                 [buddy "1.1.0"]
                 ;; Project config file support
                 [yogthos/config "0.8"]

                 ;; ClojureScript libraries
                 [reagent "0.6.0"]
                 [secretary "1.2.3"]
                 [kibu/pushy "0.3.6"]
                 [cljs-ajax "0.5.8"]
                 [cljs-http "0.1.41"]
                 [cljsjs/jquery "2.2.2-0"]
                 [cljsjs/semantic-ui "2.2.4-0"]]
  :min-lein-version "2.6.1"
  :jvm-opts ["-Xms200m"
             "-Xmx400m"
             "-server"
             "-XX:+TieredCompilation"
             "-XX:+AggressiveOpts"]
  :source-paths ["src/clj" "script"]
  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-bower "0.5.1"]
            [lein-ring "0.9.7"]
            [lein-ancient "0.6.10"]
            [cider/cider-nrepl "0.13.0"]
            [refactor-nrepl "2.2.0"]
            [lein-figwheel "0.5.7"]]
  :clean-targets ^{:protect false} ["target"]
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs" "script"]
     :figwheel true
     :compiler {:main "sysrev-web.main"
                :output-to "resources/public/out-dev/sysrev_web.js"
                :output-dir "resources/public/out-dev"
                :asset-path "/out"
                ;; :preloads      [devtools.preload]
                :optimizations :none
                :pretty-print true
                :source-map true
                :source-map-timestamp true}}
    {:id "production"
     :source-paths ["src/cljs"]
     :compiler {:main "sysrev-web.main"
                :output-to "resources/public/out-production/sysrev_web.js"
                :output-dir "resources/public/out-production"
                :asset-path "/out"
                :closure-defines {goog.DEBUG false}
                :optimizations :advanced
                :pretty-print false
                :source-map "resources/public/out-production/sysrev_web.js.map"
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
              :main sysrev.web.main
              :aot [sysrev.web.main]}
             :dev
             {:resource-paths ["config/dev"]}})
