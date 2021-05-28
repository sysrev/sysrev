(ns sysrev.e2e.menu-test
  (:require [sysrev.e2e.interface :as e2e]
            [sysrev.fixtures.interface :as fixtures])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each fixtures/wrap-fixtures)

#_:clj-kondo/ignore
(deftest test-search-users
  (testing "username displays correctly in menu"
    (e2e/doto-driver driver
      (e2e/log-in-as "test_user_1@insilica.co")
      (e2e/wait-is-visible? [{:fn/has-class "menu"}
                             {:fn/has-text "test-user-1"}]))))
