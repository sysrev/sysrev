(ns sysrev.test.stripe
  (:require [clojure.test :refer :all]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [sysrev.test.browser.core :refer [create-test-user test-login]]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

#_
(deftest create-user-subscribe
  (let [{:keys [email password]} test-login
        new-user (create-test-user)
        plan-name "Basic"]
    ;; register user to stripe
    (users/create-sysrev-stripe-customer! new-user)
    (let [user (users/user-by-email email)]
      ;; subscribe user to a free plan
      (is (= "subscription"
             (:object (stripe/subscribe-customer! user plan-name))))
      ;; delete this user on stripe
      (is (:deleted (stripe/delete-customer! user))))))
