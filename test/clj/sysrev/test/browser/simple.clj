(ns sysrev.test.browser.simple
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.browser.core :as browser :refer
             [webdriver-fixture-once webdriver-fixture-each go-route
              login-form-shown? panel-rendered? element-rendered? wait-until-panel-exists]]
            [sysrev.test.browser.navigate :refer
             [log-in log-out register-user]]
            [clojure.string :as str]
            [sysrev.db.users :refer [delete-user create-user]]))

(use-fixtures :once default-fixture webdriver-fixture-once)
(use-fixtures :each webdriver-fixture-each)

#_ (deftest home-page-loads
  (go-route "/")
  (is (login-form-shown?)))

(deftest project-routes
  (log-in)
  (wait-until-panel-exists [:project])
  (is (panel-rendered? [:project]))
  (Thread/sleep 500)
  (is (or (panel-rendered? [:project :project :overview])
          (panel-rendered? [:project :project :add-articles])))
  (is (not (panel-rendered? [:project :project :fake-panel])))
  (go-route "/project/labels")
  (wait-until-panel-exists [:project :project :labels :view])
  (is (panel-rendered? [:project :project :labels :view]))
  (is (not (panel-rendered? [:project :project :overview])))
  (go-route "/project/labels/edit")
  (wait-until-panel-exists [:project :project :labels :edit])
  (is (panel-rendered? [:project :project :labels :edit]))
  (go-route "/project/settings")
  (wait-until-panel-exists [:project :project :settings])
  (is (panel-rendered? [:project :project :settings]))
  (go-route "/project/invite-link")
  (wait-until-panel-exists [:project :project :invite-link])
  (is (panel-rendered? [:project :project :invite-link]))
  (go-route "/project/export")
  (wait-until-panel-exists [:project :project :export-data])
  (is (panel-rendered? [:project :project :export-data]))
  (go-route "/project/user")
  (wait-until-panel-exists [:project :user :labels])
  (is (panel-rendered? [:project :user :labels]))
  (go-route "/project/review")
  (wait-until-panel-exists [:project :review])
  (is (panel-rendered? [:project :review]))
  (go-route "/project/articles")
  (wait-until-panel-exists [:project :project :articles])
  (is (panel-rendered? [:project :project :articles]))
  (go-route "/")
  (Thread/sleep 500)
  (is (or (panel-rendered? [:project :project :overview])
          (panel-rendered? [:project :project :add-articles])))
  (go-route "/user/settings")
  (wait-until-panel-exists [:user-settings])
  (is (panel-rendered? [:user-settings]))
  (taxi/wait-until #(taxi/exists? (taxi/find-element {:css "a[id='log-out-link']"})))
  (log-out)
  (is (login-form-shown?)))

#_
(deftest invalid-route-redirect
  (let [paths ["/x"]]
    (doseq [path paths]
      (go-route path)
      (is (login-form-shown?)
          (format "Invalid path should go to /" path)))))

#_
(deftest register-test-account
  (go-route "/register")
  (register-user)
  (is (str/includes? (taxi/text "body")
                     "Please select the project")))
