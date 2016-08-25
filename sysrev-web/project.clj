(defproject sysrev-web "0.1.0-SNAPSHOT"
  :dependencies [;; Clojure (JVM) libraries
                 ;;
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.385"]
                 ;; REPL
                 [org.clojure/tools.nrepl "0.2.12"]
                 [com.cemerick/piggieback "0.2.1"]
                 [figwheel-sidecar "0.5.4-7"]

                 ;; ClojureScript libraries
                 [org.clojure/clojurescript "1.9.93"]
                 [reagent "0.6.0-rc"]
                 [secretary "1.2.3"]
                 [kibu/pushy "0.3.6"]
                 [cljs-ajax "0.5.8"]
                 [cljs-http "0.1.41"]]

  :min-lein-version "2.6.1"
  :jvm-opts ["-Xms200m"
             "-Xmx400m"
             "-server"
             "-XX:+TieredCompilation"
             "-XX:+AggressiveOpts"]
  :source-paths ["src/clj" "script"]
  :plugins [[lein-cljsbuild "1.1.3"]
            [lein-bower "0.5.1"]
            [lein-ring "0.9.7"]
            [lein-ancient "0.6.10"]
            [cider/cider-nrepl "0.12.0"]
            [refactor-nrepl "2.2.0"]
            [lein-figwheel "0.5.4-7"]]
  :bower-dependencies [[jquery "3.1.0"]]
  :clean-targets ^{:protect false}
  ["resources/public/out-dev"
   "resources/public/out-production"
   "target"]
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs"]
     :figwheel true
     :compiler {:main "sysrev-web.main"
                :output-to "resources/public/out-dev/sysrev_web.js"
                :output-dir "resources/public/out-dev"
                :asset-path "/out-dev"
;;                :preloads      [devtools.preload]
                :optimizations :none
                :pretty-print true
                :source-map true
                :source-map-timestamp true}}
    {:id "production"
     :source-paths ["src/cljs"]
     :compiler {:main "sysrev-web.main"
                :output-to "resources/public/out-production/sysrev_web.js"
                :output-dir "resources/public/out-production"
                :asset-path "/out-production"
                :closure-defines {goog.DEBUG false}
                :optimizations :advanced
                :pretty-print false
                :source-map "resources/public/out-production/sysrev_web.js.map"
                :source-map-timestamp true}}]}
  :figwheel {:nrepl-port 7888
             ;; these should work with both Cider and Cursive
             :nrepl-middleware ["cider.nrepl/cider-middleware"
                                "refactor-nrepl.middleware/wrap-refactor"
                                "cemerick.piggieback/wrap-cljs-repl"]
             :css-dirs ["resources/public/css"]})
