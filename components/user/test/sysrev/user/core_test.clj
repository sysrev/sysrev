(ns sysrev.user.core-test
  (:require [clojure.string :as str]
            [orchestra.spec.test :as st]
            #_[sysrev.fixtures.interface :refer [wrap-fixtures]])
  (:use clojure.test
        #_sysrev.user.core))

(st/instrument)

#_(use-fixtures :each wrap-fixtures)

#_(deftest test-unique-username
  (testing "Unique usernames with no conflicts"
    (is (= "test-user-0xff" (unique-username "test_user_0xff@insilica.co"))))
  (testing "Unique usernames with conflicts"
    (is (str/starts-with? (unique-username "test_user_1@insilica.co") "test-user-1-")))
  (testing "Very long usernames with conflicts"
    (create-user "a123456789012345678901234567890123456789@insilica.co" "override")
    (is (java.util.UUID/fromString (unique-username "a123456789012345678901234567890123456789@insilica.co")))))
