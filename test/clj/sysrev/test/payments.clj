(ns sysrev.test.payments
  (:require [clojure.test :refer :all]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [sysrev.db.users :as users]
            [sysrev.payments :as payments]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

;; first, make sure testing profiles.clj will work

(deftest create-user-subscribe
  (let [email "foo@bar.com"
        password "foobar"
        new-user (users/create-user email password)
        plan-name "Basic"]
    ;; register user to stripe
    (users/create-sysrev-stripe-customer! new-user)
    
    (let [new-user (users/get-user-by-email email)]
      ;; subscribe user to a free plan
      (is (= "subscription"
             (:object (payments/subscribe-customer! new-user plan-name))))
      ;; delete this user on stripe
      (is (:deleted (payments/delete-customer! new-user))))))
