(ns sysrev.test.e2e.dev-server-test
  (:require [clojure.tools.logging :as log]
            [clojure.test :refer (deftest is testing)]
            popen))

(defn log-stderr! [name process]
  (with-open [reader (popen/stderr process)]
    (doseq [line (line-seq reader)]
      (log/error name line))))

(defn log-stdout! [name process]
  (with-open [reader (popen/stdout process)]
    (doseq [line (line-seq reader)]
      (log/info name line))))

(deftest ^:integration test-dev-server
  (testing "Embedded dev server starts up and shuts down successfully"
    (let [process (popen/popen ["clj" "-M:dev-embedded:dev:test:repl" "0""--exit"])
          _ (future (log-stderr! "dev-embedded" process))
          _ (future (log-stdout! "dev-embedded" process))
          exit-code (-> (future (popen/join process))
                        (deref 300000 ::timeout))]
      (try
        (is (= 0 exit-code))
        (finally
          (popen/kill process))))))
