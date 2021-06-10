(ns sysrev.notification.interface-test
  (:require [orchestra.spec.test :as st]
            [sysrev.fixtures.interface :refer [wrap-fixtures]])
  (:use clojure.test
        sysrev.notification.interface))

(st/instrument)

(use-fixtures :each wrap-fixtures)

(deftest create-notification-test
  (is (integer? (create-notification {:text "Test-System-Notification"
                                      :uri "/test-system-uri"
                                      :type :system}))))
