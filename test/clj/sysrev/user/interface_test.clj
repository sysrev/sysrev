(ns sysrev.user.interface-test
  (:require
   [clojure.test :refer :all]
   [sysrev.fixtures.interface :as fixtures]
   [sysrev.test.core :as test]
   [sysrev.user.interface :as user]))

(deftest ^:integration test-change-username
  (test/with-test-system [{:keys [postgres]} {:isolate? true}]
    (fixtures/load-fixtures! postgres)
    (testing "Changing username of nonexistent user"
      (is (zero? (user/change-username 1000000 "69c2124b-7da8"))))
    (testing "Changing username"
      (is (= 1 (user/change-username 1000001 "69c2124b-7da8")))
      (is (= "test_user_1@insilica.co" (:email (user/user-by-username "69c2124b-7da8")))))))

(deftest ^:integration test-get-users-public-info
  (test/with-test-system [{:keys [postgres]} {:isolate? true}]
    (fixtures/load-fixtures! postgres)
    (testing "Public info for nonexistent user"
      (is (empty? (user/get-users-public-info [1000000]))))
    (let [test-user-1 {:user-id 1000001
                       :date-created nil
                       :user-uuid #uuid "f05fb191-0a28-4d37-a324-db4566128d12"
                       :username "test-user-1"
                       :introduction nil}
          test-user-2 {:user-id 1000002
                       :date-created nil
                       :user-uuid #uuid "8845a64c-836f-46fd-8a0d-61fd50952a85"
                       :username "test-user-2"
                       :introduction nil}]
      (testing "Public info returns allowed fields"
        (is (= [test-user-1] (user/get-users-public-info [1000001])))
        (is (= #{test-user-1 test-user-2}
               (set (user/get-users-public-info [1000001 1000002])))))
      (testing "Mixture of valid and nonexistent users"
        (is (= [test-user-1] (user/get-users-public-info [1000000 1000001])))
        (is (= [test-user-1] (user/get-users-public-info [1000001 1000000])))
        (is (= #{test-user-1 test-user-2}
               (set (user/get-users-public-info [1000002 1000001 1000000]))))))))

(deftest ^:integration test-search-users
  (test/with-test-system [{:keys [postgres]} {:isolate? true}]
    (fixtures/load-fixtures! postgres)
    (testing "Search with no results"
      (is (empty? (user/search-users "69c2124b"))))
    (let [test-user-1 {:user-id 1000001
                       :date-created nil
                       :user-uuid #uuid "f05fb191-0a28-4d37-a324-db4566128d12"
                       :username "test-user-1"
                       :introduction nil}]
      (testing "Exact search"
        (is (= [test-user-1] (user/search-users "test-user-1"))))
      (testing "Prefix search"
        (is (= test-user-1 (first (user/search-users "test-user")))))
      (testing "Case-insensitive exact search"
        (is (= [test-user-1] (user/search-users "TEST-USER-1"))))
      (testing "Case-insensitive prefix search"
        (is (= test-user-1 (first (user/search-users "TEST-USER"))))))))

(deftest ^:integration test-user-by-id
  (test/with-test-system [{:keys [postgres]} {:isolate? true}]
    (fixtures/load-fixtures! postgres)
    (is (nil? (user/user-by-id 1000000)))
    (is (= "test_user_1@insilica.co" (:email (user/user-by-id 1000001))))
    (is (= "test-user-1" (:username (user/user-by-id 1000001))))))

(deftest ^:integration test-user-by-username
  (test/with-test-system [{:keys [postgres]} {:isolate? true}]
    (fixtures/load-fixtures! postgres)
    (is (nil? (user/user-by-username "07d87f33-6926")))
    (is (= "test_user_1@insilica.co" (:email (user/user-by-username "test-user-1"))))))
