(ns sysrev.e2e.search-test
  (:require [sysrev.e2e.interface :as e2e]
            [sysrev.fixtures.interface :as fixtures])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each fixtures/wrap-fixtures)

#_:clj-kondo/ignore
(deftest test-search-users
  (testing "usernames are correct"
    (e2e/doto-driver driver
      (go (e2e/path "/search?q=test-user&p=1&type=users"))
      (-> (e2e/wait-is-visible? {:fn/has-text "test-user-1"})))))
