(ns sysrev.browser-test-main
  (:gen-class)
  (:require [clojure.test :refer [*test-out* run-all-tests]]
            [clojure.test.junit :refer [with-junit-output]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            sysrev.test.all
            [sysrev.test.core :refer [get-selenium-config]]
            [clojure.pprint :as pprint]))

(defn -main [& _args]
  (log/info (str "running browser tests with config:\n"
                 (pprint/write (get-selenium-config) :stream nil)))
  (let [fname "target/junit-browser.xml"
        {:keys [fail error] :as summary}
        (with-open [w (io/writer fname)]
          (binding [*test-out* w]
            (with-junit-output
              (run-all-tests #"sysrev\.test\.browser\..*"))))]
    (log/info "summary:\n"
              (pprint/write summary :stream nil))
    (log/info "wrote junit results to " fname)
    (if (and (= fail 0) (= error 0))
      (System/exit 0)
      (System/exit 1))))
