(ns sysrev.user.interface-test
  (:require [orchestra.spec.test :as st]
            [sysrev.fixtures.interface :refer [wrap-fixtures]])
  (:use clojure.test
        sysrev.user.interface))

(st/instrument)

(use-fixtures :each wrap-fixtures)

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

(deftest test-search-users
  (testing "Search with no results"
    (is (empty? (search-users "69c2124b"))))
  (let [test-user-1 {:user-id 1000001
                     :date-created nil
                     :username "test-user-1"
                     :introduction nil}]
    (testing "Exact search"
      (is (= [test-user-1] (search-users "test-user-1"))))
    (testing "Prefix search"
      (is (= test-user-1 (first (search-users "test-user")))))
    (testing "Case-insensitive exact search"
      (is (= [test-user-1] (search-users "TEST-USER-1"))))
    (testing "Case-insensitive prefix search"
      (is (= test-user-1 (first (search-users "TEST-USER")))))))

(deftest test-user-by-id
  (is (nil? (user-by-id 1000000)))
  (is (= "test_user_1@insilica.co" (:email (user-by-id 1000001))))
  (is (= "test-user-1" (:username (user-by-id 1000001)))))

(deftest test-user-by-username
  (is (nil? (user-by-username "07d87f33-6926")))
  (is (= "test_user_1@insilica.co" (:email (user-by-username "test-user-1")))))
