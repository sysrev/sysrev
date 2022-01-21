(ns sysrev.notification.interface-test
  (:require
   [clojure.test :refer :all]
   [sysrev.notification.interface :as notification]
   [sysrev.test.core :as test]))

(deftest ^:integration create-notification-test
  (test/with-test-system [_ {:isolate? true}]
    (is (integer? (notification/create-notification
                   {:text "Test-System-Notification"
                    :uri "/test-system-uri"
                    :type :system})))))
