(ns sysrev.all-test-main
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.test :refer [*test-out*]]
   [clojure.tools.logging :as log]
   [eftest.report.junit]
   [eftest.runner :refer [find-tests run-tests]]
   [sysrev.config :refer [env]]
   [sysrev.test.core :as test]))

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
