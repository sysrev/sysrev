(ns sysrev.test.browser.simple
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.browser.core :as browser :refer
             [webdriver-fixture-once webdriver-fixture-each]]
            [sysrev.test.browser.navigate :as nav]
            [clojure.string :as str]))

(use-fixtures :once default-fixture webdriver-fixture-once)
(use-fixtures :each webdriver-fixture-each)

(defn root-panel-exists? []
  (taxi/wait-until
   #(or (browser/panel-exists? [:project :project :overview]
                               :wait? false)
        (browser/panel-exists? [:project :project :add-articles]
                               :wait? false))
   10000 100)
  (or (browser/panel-exists? [:project :project :overview]
                             :wait? false)
      (browser/panel-exists? [:project :project :add-articles]
                             :wait? false)))

(deftest project-routes
  (nav/log-in)
  (nav/open-first-project)
  (is (browser/panel-exists? [:project]))
  (is (root-panel-exists?))
  (is (not (browser/panel-exists? [:project :project :fake-panel]
                                  :wait? false)))

  (browser/go-project-route "/labels/edit")
  (is (browser/panel-exists? [:project :project :labels :edit]))

  (browser/go-project-route "/settings")
  (is (browser/panel-exists? [:project :project :settings]))

  (browser/go-project-route "/invite-link")
  (is (browser/panel-exists? [:project :project :invite-link]))

  (browser/go-project-route "/export")
  (is (browser/panel-exists? [:project :project :export-data]))

  (browser/go-project-route "/user")
  (is (browser/panel-exists? [:project :user :labels]))

  (browser/go-project-route "/review")
  (is (browser/panel-exists? [:project :review]))

  (browser/go-project-route "/articles")
  (is (browser/panel-exists? [:project :project :articles]))

  (browser/go-project-route "")
  (is (root-panel-exists?))

  (browser/go-route "/user/settings")
  (is (browser/panel-exists? [:user-settings]))

  (is (browser/exists? {:css "a#log-out-link"}))
  (nav/log-out)
  (is (browser/login-form-shown?)))
