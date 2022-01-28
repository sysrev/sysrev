(defproject sysrev-web "0.1.0-SNAPSHOT"
  ;; dependencies are read from deps.edn by the lein-tools-deps plugin

  :min-lein-version "2.8.1"
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
  :aliases {"build-prod"   ["with-profile" "+prod" "uberjar"]
            "run-tests"    ["with-profile" "+test-config" "eftest"]
            "jenkins"      ["with-profile" "+jenkins" "eftest"]
            "junit"        ["with-profile" "+test,+test-all" "run"]
            "repl"         ["run" "-m" "sysrev.user"]
            "test-prod"    ["with-profile" "+test-remote,+test-browser,+test-prod" "run"]
            "test-staging" ["with-profile" "+test-remote,+test-browser,+test-staging" "run"]}
  :clean-targets ^{:protect false} ["target"]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :plugins [[org.clojars.john-shaffer/lein-tools-deps "0.4.6-1"]]
  :repl-options {:timeout 120000
                 :init-ns sysrev.user}
  :profiles {:prod           {:lein-tools-deps/config {:aliases [:prod]}
                              :main sysrev.main
                              :aot [sysrev.main]}
             :test-browser   {:lein-tools-deps/config {:aliases [:test]}
                              :main sysrev.browser-test-main}
             :test-all       {:lein-tools-deps/config {:aliases [:test]}
                              :main sysrev.all-test-main}
             :test-staging   {:lein-tools-deps/config {:aliases [:test-staging :test]}}
             :test-prod      {:lein-tools-deps/config {:aliases [:test-prod :test]}}
             :test-s3-dev    {:lein-tools-deps/config {:aliases [:test-s3-dev :test]}}
             :dev            {:lein-tools-deps/config {:aliases [:dev :test]}
                              :jvm-opts ["-Xmx1200m"
                                         "-Djdk.attach.allowAttachSelf=true"]
                              :plugins [[lein-eftest "0.5.9"]]}
             :repl           {:plugins [[lein-environ "1.2.0"]]}
             :test           {:lein-tools-deps/config {:aliases [:test]}
                              :jvm-opts ["-Xmx1000m"]}
             :test-remote    {:lein-tools-deps/config {:aliases [:test]}
                              :jvm-opts ["-Xmx1000m"]}
             :jenkins        {:lein-tools-deps/config {:aliases [:test]}
                              :eftest {:report eftest.report.junit/report
                                       :report-to-file "target/junit.xml"
                                       :thread-count 4}}})
