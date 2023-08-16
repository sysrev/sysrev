(defproject sysrev-web "0.1.0-SNAPSHOT"
  ;; dependencies are read from deps.edn by the lein-tools-deps plugin
  :min-lein-version "2.8.1"
  :jvm-opts ["-Djava.util.logging.config.file=resources/logging.properties"
             "-server"]
  :source-paths ["src/clj" "src/cljc"]
  :aliases {"build-prod" ["with-profile" "+prod" "uberjar"]
            "repl"       ["run" "-m" "sysrev.user"]}
  :clean-targets ^{:protect false} ["target"]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :plugins [[org.clojars.john-shaffer/lein-tools-deps "0.4.6-1"]]
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:dev            {:lein-tools-deps/config {:aliases [:dev :test]}
                              :jvm-opts ["-Xmx1200m"
                                         "-Djdk.attach.allowAttachSelf=true"]
                              :plugins [[lein-eftest "0.6.0"]]}
             :prod           {:lein-tools-deps/config {:aliases [:prod]}
                              :main sysrev.main
                              :aot [sysrev.main]}
             :repl           {:plugins [[lein-environ "1.2.0"]]}
             :test           {:lein-tools-deps/config {:aliases [:test]}
                              :jvm-opts ["-Xmx1000m"]}})
