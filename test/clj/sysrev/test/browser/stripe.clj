(ns sysrev.test.browser.stripe
  (:require [clj-stripe.customers :as customers]
            [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [sysrev.api :as api]
            [sysrev.db.plans :as plans]
            [sysrev.db.users :as users]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.test.browser.core :as browser]
            [sysrev.test.browser.navigate :as navigate]
            [sysrev.stripe :as stripe]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

(deftest register-and-subscribe-to-basic-plan
  (let [email "foo@bar.com"
        password "foobar"]
    (navigate/register-user email password)
    ;; after registering, does the stripe customer exist?
    (is (= email
           (:email (stripe/execute-action
                    (customers/get-customer
                     (:stripe-id (users/get-user-by-email email)))))))
    ;; does stripe think the customer is registered to a basic plan?
    (is (= api/default-plan
           (-> (stripe/execute-action
                (customers/get-customer
                 (:stripe-id (users/get-user-by-email email))))
               :subscriptions :data first :items :data first :plan :name)))
    ;; do we think the user is subscribed to a basic plan?
    (is (= api/default-plan
           (plans/get-user-plan (users/get-user-by-email email))))
    ;; clean up
    (let [user (users/get-user-by-email email)]
      (users/delete-user (:user-id user))
      (is (:deleted (stripe/delete-customer! user))))))
