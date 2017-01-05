(ns sysrev.profile
  (:require [clojure.test :refer :all]
            [sysrev.db.users :refer [create-user get-user-by-email, delete-user]]
            [sysrev.connect :refer [config-fixture]]
            [sysrev.db.project :refer [create-project]])
  (:import (java.sql BatchUpdateException)))



(def user
  {:email "test@example.com"
   :password "testpassword"})


(use-fixtures :once
  config-fixture
  (fn [f]
    (let [{email :email
           password :password} user]
      (create-user email password)
      (f)
      (let [user (get-user-by-email email)]
        (delete-user (:user_id user)))
      (is (nil? (get-user-by-email email))))))


(deftest double-create-user-test
  (let [{email :email
         password :password} user]
    ;; Test exception on double insert
    (is (thrown? BatchUpdateException
                 (create-user email password)))))

(deftest create-project-test
  (let [{email :email} user
        dbuser (get-user-by-email email)]
    (let [query-result (create-project "test")]
      (is (integer? (:project_id query-result))))))
