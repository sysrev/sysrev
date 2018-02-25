(ns sysrev.test.profile
  (:require
   [clojure.test :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as t]
   [clojure.tools.logging :as log]
   [sysrev.test.core :refer [default-fixture completes?]]
   [sysrev.test.browser.core :refer
    [test-login create-test-user delete-test-user]]
   [sysrev.db.users :as users]
   [sysrev.db.project :refer [create-project delete-project]])
  (:import (java.sql BatchUpdateException)
           (org.postgresql.util PSQLException)))

(use-fixtures :once
  default-fixture
  (fn [f]
    (let [{:keys [email password]} test-login]
      (create-test-user)
      (f)
      (delete-test-user)
      (is (nil? (users/get-user-by-email email))))))

(deftest double-create-user-test
  (let [{:keys [email password]} test-login]
    ;; Test exception on double insert
    (is (thrown? PSQLException
                 (users/create-user email password)))))

(deftest create-project-test
  (let [query-result (create-project "test")]
    (is (integer? (:project-id query-result)))
    (is (completes?
         (delete-project (:project-id query-result))))))
