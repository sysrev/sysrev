(ns sysrev.e2e.group-test
  (:require [sysrev.e2e.interface :as e2e]
            [sysrev.fixtures.interface :as fixtures])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each fixtures/wrap-fixtures)

#_:clj-kondo/ignore
(deftest test-group-users
  (testing "usernames are correct"
    (e2e/doto-driver driver
      (e2e/log-in-as "test_user_1@insilica.co")
      (go (e2e/path "/org/110001/users"))
      (e2e/wait-is-visible? {:fn/has-text "test-user-1"})
      (e2e/wait-is-visible? {:fn/has-text "test-user-2"}))))
