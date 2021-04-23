(ns sysrev.test.etaoin.notifications
  (:require [etaoin.api :as ea]
            [medley.core :as medley]
            [sysrev.project.core :refer [create-project]]
            [sysrev.project.invitation :refer [create-invitation!]]
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

(defn create-projects-and-invitations! [inviter-id user-id]
  (let [project-a (create-project "Mangiferin")
        project-b (create-project "EntoGEM")]
    (create-invitation! user-id (:project-id project-a) inviter-id "paid-reviewer")
    (create-invitation! user-id (:project-id project-b) inviter-id "paid-reviewer")
    [project-a project-b]))

(deftest-etaoin notifications-button
  (let [inviter-id (-> (account/create-account) :email user-by-email :user-id)
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*]
    (testing "Notifications button and drop-down work when empty."
      (doto driver
        (ea/click-visible {:fn/has-class :notifications-icon})
        (ea/wait-visible {:fn/has-text "You don't have any notifications yet"})))
    (let [[project-a project-b]
          #__ (create-projects-and-invitations! inviter-id user-id)]
      (testing "Notifications button and drop-down work."
        (doto driver
          (ea/refresh)
          (ea/wait 1)
          (do (e/take-screenshot))
          (ea/wait-visible {:fn/has-class :notifications-count
                            :fn/has-text "2"})
          (ea/click-visible {:fn/has-class :notifications-icon})
          (ea/click-visible {:fn/has-text (:name project-a)})
          (ea/wait 1))
        (is (= (str "/user/" user-id "/invitations") (e/get-path))))
      (testing "Notifications are removed after being clicked."
        (e/go "/")
        (doto driver
          (ea/wait-visible {:fn/has-class :notifications-count
                            :fn/has-text "1"})
          (ea/click-visible {:fn/has-class :notifications-icon})
          (ea/wait 1))
        (is (not (ea/visible? driver {:fn/has-text (:name project-a)})))
        (is (ea/visible? driver {:fn/has-text (:name project-b)}))))))

(deftest-etaoin notifications-page
  (let [inviter-id (-> (account/create-account) :email user-by-email :user-id)
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*]
    (testing "Notifications page works when empty."
      (doto driver
        (ea/click-visible {:fn/has-class :notifications-icon})
        (ea/click-visible {:fn/has-class :notifications-footer})
        (ea/wait-visible {:fn/has-text "You don't have any notifications yet"}))
      (is (= "/notifications" (e/get-path))))
    (let [[project-a project-b]
          #__ (create-projects-and-invitations! inviter-id user-id)]
      (testing "Notifications page works."
        (e/go "/")
        (doto driver
          (ea/click-visible {:fn/has-class :notifications-icon})
          (ea/click-visible {:fn/has-class :notifications-footer}))
        (is (= "/notifications" (e/get-path)))
        (doto driver
          (ea/click-visible {:fn/has-text (:name project-b)})
          (ea/wait 1))
        (is (= (str "/user/" user-id "/invitations") (e/get-path)))))))
