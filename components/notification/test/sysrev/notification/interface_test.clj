(ns sysrev.notification.interface-test
  (:require [orchestra.spec.test :as st]
            #_[sysrev.fixtures.interface :refer [wrap-fixtures]])
  (:use clojure.test
        #_sysrev.notification.interface))

(st/instrument)

#_(use-fixtures :each wrap-fixtures)

#_(deftest create-notification-test
  (is (integer? (create-notification {:text "Test-System-Notification"
                                      :uri "/test-system-uri"
                                      :type :system}))))
