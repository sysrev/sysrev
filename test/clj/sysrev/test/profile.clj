(ns sysrev.test.profile
  (:require
   [clojure.test :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as t]
   [clojure.tools.logging :as log]
   [sysrev.test.core :refer [default-fixture completes?]]
   [sysrev.db.users :refer [create-user get-user-by-email delete-user]]
   [sysrev.db.project :refer [create-project delete-project]])
  (:import (java.sql BatchUpdateException)
           (org.postgresql.util PSQLException)))

(def user
  {:email "test@example.com"
   :password "testpassword"})

(use-fixtures :once
  default-fixture
  (fn [f]
    (let [{email :email
           password :password} user]
      (create-user email password)
      (f)
      (let [user (get-user-by-email email)]
        (delete-user (:user-id user)))
      (is (nil? (get-user-by-email email))))))

(deftest double-create-user-test
  (let [{email :email
         password :password} user]
    ;; Test exception on double insert
    (is (thrown? PSQLException
                 (create-user email password)))))

(deftest create-project-test
  (let [{email :email} user
        dbuser (get-user-by-email email)]
    (let [query-result (create-project "test")]
      (is (integer? (:project-id query-result)))
      (is (completes?
           (delete-project (:project-id query-result)))))))
