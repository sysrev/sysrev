(ns sysrev.test.e2e.group-test
  (:require
   [clojure.test :refer :all]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.group.core :as group]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e test-group-users
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-1 (test/create-test-user system)
          user-2 (test/create-test-user system)
          group-id (group/create-group! "test-group-users")]
      (group/add-user-to-group! (:user-id user-1) group-id)
      (group/add-user-to-group! (:user-id user-2) group-id)
      (account/log-in test-resources user-1)
      (e/go test-resources (str "/org/" group-id "/users"))
      (testing "usernames display correctly"
        (et/is-wait-visible driver {:fn/has-text (:username user-1)})
        (et/is-wait-visible driver {:fn/has-text (:username user-2)})))))
