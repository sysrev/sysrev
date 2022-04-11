(ns sysrev.notification.interface-test
  (:require
   [clojure.test :refer :all]
   [sysrev.api :as api]
   [sysrev.group.core :as group]
   [sysrev.notification.interface :as notification]
   [sysrev.test.core :as test]))

(deftest ^:integration test-create-notification
  (test/with-test-system [_ {}]
    (is (integer? (notification/create-notification
                   {:text "Test-System-Notification"
                    :uri "/test-system-uri"
                    :type :system})))))

(deftest ^:integration test-group-project-notifications
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (testing "Adding users and changing roles don't generate :project-has-new-user notifications (#4, #5)"
      (let [{owner-id :user-id} (test/create-test-user system)
            {member-id :user-id} (test/create-test-user system)
            group-id (group/create-group! "test-role-change")]
        (group/add-user-to-group! owner-id group-id :permissions ["owner"])
        (group/add-user-to-group! member-id group-id)
        (api/create-project-for-org! "test-role-change" owner-id group-id true)
        (api/set-user-group-permissions! member-id group-id ["admin"])
        (is (not (some #{:project-has-new-user :project-has-new-user-combined}
                       (->> (api/user-notifications-new sr-context owner-id)
                            :notifications
                            (map (comp :type :content))))))))))
