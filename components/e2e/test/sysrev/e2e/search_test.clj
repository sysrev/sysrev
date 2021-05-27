(ns sysrev.e2e.search-test
  (:require [sysrev.e2e.interface :as e2e]
            [sysrev.fixtures.interface :as fixtures])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each fixtures/wrap-fixtures)

#_:clj-kondo/ignore
(defn search [driver]
  (doto driver
    (go (e2e/path "/"))
    (wait-visible {:id "search-sysrev-bar"})
    (fill-human {:id "search-sysrev-bar"} "test-user")
    (click-visible [{:id "search-sysrev-form"} {:tag :button}])))

(defn search-users [driver]
  (search driver)
  (click-visible driver {:fn/text "Users"}))

#_:clj-kondo/ignore
(deftest test-search-users
  (testing "usernames display correctly"
    (e2e/doto-driver driver
      (search-users)
      (e2e/wait-is-visible? {:fn/has-text "test-user-1"}))
    (e2e/doto-driver driver
      (go (e2e/path "/search?q=test-user&p=1&type=users"))
      (e2e/wait-is-visible? {:fn/has-text "test-user-1"}))))
