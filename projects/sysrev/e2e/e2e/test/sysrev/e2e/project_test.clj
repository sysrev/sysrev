(ns sysrev.e2e.project-test
  (:require [sysrev.e2e.interface :as e2e])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each e2e/test-server-fixture)

#_:clj-kondo/ignore
(deftest test-clone-project
  (testing "username displays correctly"
    (e2e/doto-driver driver
      (e2e/log-in-as "test_user_1@insilica.co")
      (go (e2e/path "/p/21696"))
      (click-visible {:id "clone-button"})
      (e2e/wait-is-visible? [{:fn/has-class "clone-project"} {:fn/has-text "test-user-1"}]))))

#_:clj-kondo/ignore
(deftest test-project-users
  (testing "usernames are correct"
    (e2e/doto-driver driver
      (go (e2e/path "/p/21696/users"))
      (e2e/wait-is-visible? {:fn/has-text "test-user-1"}))))
