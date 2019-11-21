(ns sysrev.test.browser.ctgov
  (:require [clojure.test :refer [is use-fixtures]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.core :as test :refer [default-fixture]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)


#_(deftest-browser happy-path
    (and (test/db-connected?) (not (test/remote-test?)))
    [project-name "SysRev Browser Test (clinicaltrials.gov)"
     search-term "heart attack"]
    (do (nav/log-in)
        (nav/new-project project-name))
    :cleanup (constantly true))
