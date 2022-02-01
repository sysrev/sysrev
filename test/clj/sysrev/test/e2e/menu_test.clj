(ns sysrev.test.e2e.menu-test
  (:require
   [clojure.test :refer :all]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e test-menu-username
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [username] :as user} (test/create-test-user system)]
      (account/log-in test-resources user)
      (testing "username displays correctly in menu"
        (et/is-wait-visible driver [{:fn/has-class :menu}
                                    {:fn/has-text username}])))))
