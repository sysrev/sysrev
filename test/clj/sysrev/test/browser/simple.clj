(ns sysrev.test.browser.simple
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.browser.core :refer
             [webdriver-fixture-once webdriver-fixture-each go-route
              login-form-shown?]]
            [sysrev.test.browser.navigate :refer
             [log-in register-user]]
            [clojure.string :as str]
            [sysrev.db.users :refer [delete-user create-user]]))

(use-fixtures :once default-fixture webdriver-fixture-once)
(use-fixtures :each webdriver-fixture-each)

#_
(deftest home-page-loads
  (go-route "/")
  (is (login-form-shown?)))

#_
(deftest unauthorized-pages-load
  (let [paths ["/project"
               "/user"
               "/project/labels"
               "/project/predict"
               "/project/classify"
               "/select-project"]]
    (doseq [path paths]
      (go-route path)
      (is (login-form-shown?)
          (format "Invalid content on path '%s':\n%s"
                  path (taxi/text "body"))))))

#_
(deftest invalid-route-redirect
  (let [paths ["/x"]]
    (doseq [path paths]
      (go-route path)
      (is (login-form-shown?)
          (format "Invalid path should go to /" path)))))

#_
(deftest login-page
  (go-route "/")
  (taxi/click "a[href*='login']")
  (is (str/includes? (taxi/text "body")
                     "Forgot password")))

#_
(deftest log-in-content
  (log-in)
  (is (str/includes? (taxi/text "body")
                     "Classify")))

#_
(deftest register-test-account
  (go-route "/register")
  (register-user)
  (is (str/includes? (taxi/text "body")
                     "Please select the project")))
