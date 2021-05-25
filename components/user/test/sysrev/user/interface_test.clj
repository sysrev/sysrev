(ns sysrev.user.interface-test
  (:require [orchestra.spec.test :as st]
            [sysrev.test-postgres.interface :refer [wrap-embedded-postgres]])
  (:use clojure.test
        sysrev.user.interface))

(st/instrument)

(use-fixtures :each wrap-embedded-postgres)

(deftest test-change-username
  (testing "Changing username of nonexistent user"
    (is (zero? (change-username 1000000 "69c2124b-7da8"))))
  (testing "Changing username"
    (is (= 1 (change-username 1000001 "69c2124b-7da8")))
    (is (= "test_user_1@insilica.co" (:email (user-by-username "69c2124b-7da8"))))))

(deftest test-get-users-public-info
  (testing "Public info for nonexistent user"
    (is (empty? (get-users-public-info [1000000]))))
  (let [test-user-1 {:user-id 1000001
                     :date-created nil
                     :username "test-user-1"
                     :introduction nil}
        test-user-2 {:user-id 1000002
                     :date-created nil
                     :username "test-user-2"
                     :introduction nil}]
    (testing "Public info returns allowed fields"
      (is (= [test-user-1] (get-users-public-info [1000001])))
      (is (= #{test-user-1 test-user-2}
             (set (get-users-public-info [1000001 1000002])))))
    (testing "Mixture of valid and nonexistent users"
      (is (= [test-user-1] (get-users-public-info [1000000 1000001])))
      (is (= [test-user-1] (get-users-public-info [1000001 1000000])))
      (is (= #{test-user-1 test-user-2}
             (set (get-users-public-info [1000002 1000001 1000000])))))))

(deftest test-user-by-id
  (is (nil? (user-by-id 1000000)))
  (is (= "test_user_1@insilica.co" (:email (user-by-id 1000001)))))

(deftest test-user-by-username
  (is (nil? (user-by-username "07d87f33-6926")))
  (is (= "test_user_1@insilica.co" (:email (user-by-username "test-user-1")))))
