(ns sysrev.user.interface-test
  (:require [orchestra.spec.test :as st]
            [sysrev.test-postgres.interface :refer [wrap-embedded-postgres]])
  (:use clojure.test
        sysrev.user.interface))

(st/instrument)

(use-fixtures :each wrap-embedded-postgres)

(deftest test-user-by-id
  (is (nil? (user-by-id 1000000)))
  (is (= "test_user_1@insilica.co" (:email (user-by-id 1000001)))))

(deftest test-user-by-username
  (is (nil? (user-by-username "07d87f33-6926")))
  (is (= "test_user_1@insilica.co" (:email (user-by-username "test-user-1")))))
