(ns sysrev.views.panels.project.common-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sysrev.views.panels.project.common :as common]))

(def email-params {:message "test message" :projectId 1 :user "userId"})
(def formatted-email
  (str "Project issue form from: " (:user email-params) "
    <br>
    For project: " (:projectId email-params)
   "<br>
    Message: " (:message email-params)))

(deftest format-issue-email
  (testing "format issue email"
    (is (= formatted-email (common/format-issue-email email-params)))))
