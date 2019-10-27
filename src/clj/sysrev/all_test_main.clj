(ns sysrev.all-test-main
  (:gen-class)
  (:require [clojure.test :refer [*test-out* run-all-tests]]
            [clojure.java.io :as io]
            [clojure.test.junit :refer [with-junit-output]]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pprint]
            sysrev.test.all
            [sysrev.test.core :as test]
            [sysrev.config.core :refer [env]]
            [sysrev.project.core :as project]
            [sysrev.db.migration :as migration]
            [sysrev.init :as init]))

(defn -main [& _args]
  (log/info (str "running database tests with config:\n"
                 (pprint/write (-> env :postgres) :stream nil)))
  (log/info (str "running browser tests with config:\n"
                 (pprint/write (test/get-selenium-config) :stream nil)))
  (when (and (test/db-connected?) (= (-> env :profile) :remote-test))
    (init/start-db)
    (project/cleanup-browser-test-projects)
    (migration/ensure-updated-db)
    (init/start-cassandra-db))
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
