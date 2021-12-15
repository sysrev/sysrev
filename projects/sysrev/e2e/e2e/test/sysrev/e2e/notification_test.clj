(ns sysrev.e2e.notification-test
  (:require [sysrev.e2e.interface :as e2e]
            [sysrev.project.invitation :as invitation])
  (:use clojure.test
        etaoin.api))

(use-fixtures :each e2e/test-server-fixture)

#_:clj-kondo/ignore
(deftest test-notifications-page
  (testing "Notifications page works when empty."
    (e2e/doto-driver driver
      (e2e/log-in-as "test_user_1@insilica.co")
      (go (e2e/path "/"))
      (click-visible {:fn/has-class :notifications-icon})
      (click-visible {:fn/has-class :notifications-footer})
      (e2e/wait-is-visible? {:fn/has-text "You don't have any notifications yet"})
      (-> e2e/get-path (= "/user/1000001/notifications") is)))
  (testing "Notifications page works with project invitations"
    (invitation/create-invitation! 1000001 21696 1000002 "Project invitation")
    (e2e/doto-driver driver
      (e2e/log-in-as "test_user_1@insilica.co")
      (go (e2e/path "/"))
      (click-visible {:fn/has-class :notifications-icon})
      (click-visible {:fn/has-class :notifications-footer})
      (-> e2e/get-path (= "/user/1000001/notifications") is)
      (e2e/wait-is-visible? {:fn/has-text "Mangiferin - Data Extraction"})
      (click {:fn/has-text "Mangiferin - Data Extraction"})
      (-> e2e/get-path (= "/user/1000001/invitations") is))))
