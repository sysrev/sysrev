(ns sysrev.user.interface-test
  (:require [sysrev.test-postgres.interface :refer [wrap-embedded-postgres]])
  (:use clojure.test
        sysrev.user.interface))

(use-fixtures :each wrap-embedded-postgres)

(deftest test-user-by-id
  (is (= "test_user_1@insilica.co" (:email (user-by-id 1)))))
