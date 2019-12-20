(ns sysrev.browser-test-main
  (:gen-class)
  (:require [clojure.test :refer [*test-out*]]
            [eftest.runner :refer [run-tests find-tests]]
            eftest.report.junit
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pprint]
            sysrev.test.all
            [sysrev.test.core :as test :refer [get-selenium-config]]))

(defn -main [& _args]
  (log/info (str "running browser tests with config:\n"
                 (pprint/write (get-selenium-config) :stream nil)))
  (let [fname "target/junit.xml"
        {:keys [fail error] :as result}
        (with-open [w (io/writer fname)]
          (binding [*test-out* w]
            (run-tests (find-tests "test/clj/sysrev/test/browser")
                       {:thread-count (min 4 (test/get-default-threads))
                        :report eftest.report.junit/report})))]
    (log/info "\nwrote junit results to" fname)
    (log/info "\nsummary:" (pr-str result))
    (if (and (= fail 0) (= error 0))
      (System/exit 0)
      (System/exit 1))))
