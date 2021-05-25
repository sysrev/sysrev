(ns sysrev.notification.interface-test
  (:require [orchestra.spec.test :as st]
            [sysrev.test-postgres.interface :refer [wrap-embedded-postgres]])
  (:use clojure.test
        sysrev.notification.interface))

(st/instrument)

(use-fixtures :each wrap-embedded-postgres)

(deftest create-notification-test
  (is (integer? (create-notification {:text "Test-System-Notification"
                                      :uri "/test-system-uri"
                                      :type :system}))))
