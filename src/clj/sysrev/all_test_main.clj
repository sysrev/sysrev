(ns sysrev.all-test-main
  (:gen-class)
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            sysrev.test.all
            [sysrev.test.core :refer [get-selenium-config]]
            [clojure.pprint :as pprint]
            [config.core :refer [env]]))

(defn -main [& args]
  (log/info (str "running database tests with config:\n"
                 (pprint/write (-> env :postgres) :stream nil)))
  (log/info (str "running browser tests with config:\n"
                 (pprint/write (get-selenium-config) :stream nil)))
  (let [{:keys [fail error] :as summary}
        (run-all-tests #"sysrev\.test\..*")]
    (if (and (= fail 0) (= error 0))
      (System/exit 0)
      (System/exit 1))))
