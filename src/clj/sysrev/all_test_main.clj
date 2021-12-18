(ns sysrev.all-test-main
  (:gen-class)
  (:require [clojure.test :refer [*test-out*]]
            [eftest.runner :refer [run-tests find-tests]]
            eftest.report.junit
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pprint]
            [sysrev.test.core :as test]
            [sysrev.config :refer [env]]
            [sysrev.postgres.interface :as postgres]
            [sysrev.project.core :as project]
            [sysrev.db.migration :as migration]))

(defn -main [& _args]
  (log/info (str "running database tests with config:\n"
                 (pprint/write (-> env :postgres) :stream nil)))
  (log/info (str "running browser tests with config:\n"
                 (pprint/write (test/get-selenium-config) :stream nil)))
  (let [fname "target/junit.xml"
        {:keys [fail error] :as result}
        (with-open [w (io/writer fname)]
          (binding [*test-out* w]
            (run-tests (find-tests "test/clj/sysrev")
                       {:thread-count (min 4 (test/get-default-threads))
                        :report eftest.report.junit/report})))]
    (log/info "\nwrote junit results to" fname)
    (log/info "\nsummary:" (pr-str result))
    (if (and (= fail 0) (= error 0))
      (System/exit 0)
      (System/exit 1))))
