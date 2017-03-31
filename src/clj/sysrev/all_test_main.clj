(ns sysrev.all-test-main
  (:gen-class)
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.test.junit :refer [with-junit-output]]
            [clojure.tools.logging :as log]
            sysrev.test.all
            [sysrev.test.core :refer [get-selenium-config]]
            [clojure.pprint :as pprint]
            [sysrev.config.core :refer [env]]))

(defn -main [& args]
  (log/info (str "running database tests with config:\n"
                 (pprint/write (-> env :postgres) :stream nil)))
  (log/info (str "running browser tests with config:\n"
                 (pprint/write (get-selenium-config) :stream nil)))
  (let [fname "target/junit-all.xml"
        {:keys [fail error] :as summary}
        (with-open [w (io/writer fname)]
          (binding [*test-out* w]
            (with-junit-output
              (run-all-tests #"sysrev\.test\..*"))))]
    (log/info "summary:\n"
              (pprint/write summary :stream nil))
    (log/info "wrote junit results to " fname)
    (if (and (= fail 0) (= error 0))
      (System/exit 0)
      (System/exit 1))))
