(ns sysrev.user.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [sysrev.test.core :as test]
   [sysrev.user.core :as user]))

(deftest ^:integration test-unique-username
  (test/with-test-system [_ {}]
    (testing "Unique usernames with no conflicts"
      (is (= "test-user-0xff" (user/unique-username "test_user_0xff@insilica.co"))))
    (testing "Unique usernames with conflicts"
      (user/create-user "user_1@insilica.co" "override")
      (is (str/starts-with? (user/unique-username "user_1@insilica.co") "user-1-")))
    (testing "Very long usernames with conflicts"
      (user/create-user "a123456789012345678901234567890123456789@insilica.co" "override")
      (is (parse-uuid (user/unique-username "a123456789012345678901234567890123456789@insilica.co"))))))
