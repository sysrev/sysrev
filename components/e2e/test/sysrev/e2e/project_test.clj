(ns sysrev.e2e.project-test
  (:require [sysrev.e2e.interface :as e2e]
            [sysrev.fixtures.interface :as fixtures])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each fixtures/wrap-fixtures)

#_:clj-kondo/ignore
(deftest test-project-users
  (testing "usernames are correct"
    (e2e/doto-driver driver
      (go "http://localhost:4061/p/21696/users")
      (-> (e2e/wait-is-visible? {:fn/has-text "test-user-1"})))))
