(ns sysrev.test.etaoin.notifications
  (:require [etaoin.api :as ea]
            [medley.core :as medley]
            [sysrev.user.core :refer [user-by-email]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.etaoin.core :as e :refer
             [*cleanup-users* deftest-etaoin etaoin-fixture]]
            [sysrev.test.etaoin.account :as account]
            [sysrev.util :as util])
  (:use clojure.test
        etaoin.api))

(use-fixtures :once default-fixture)
(use-fixtures :each etaoin-fixture)

(deftest-etaoin notifications-button
  (let [user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*]
    (testing "Notifications button and drop-down work."
      (doto driver
        (ea/wait-visible {:fn/has-classes [:notifications-count]
                          :fn/has-text "2"})
        (ea/click-visible {:fn/has-classes [:notifications-icon]})
        (ea/click-visible {:fn/has-text "EntoGEM"})
        (ea/wait 1))
      (is (= "/u/371/p/16612" (e/get-path))))
    (testing "Notifications are removed after being clicked."
      (doto driver
        (ea/wait-visible {:fn/has-classes [:notifications-count]
                          :fn/has-text "1"})
        (ea/click-visible {:fn/has-classes [:notifications-icon]})
        (ea/wait 1))
      (is (not (ea/visible? driver {:fn/has-text "EntoGEM"}))))))

(deftest-etaoin notifications-page
  (let [user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*]
    (testing "Notifications page works."
      (doto driver
        (ea/click-visible {:fn/has-classes [:notifications-icon]})
        (ea/click-visible {:fn/has-class :notifications-footer}))
      (is (= "/notifications" (e/get-path)))
      (doto driver
        (ea/click-visible {:fn/has-text "Mangiferin"})
        (ea/wait 1))
      (is (= "/u/13552/p/34469" (e/get-path))))))
