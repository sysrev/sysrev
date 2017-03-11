(ns sysrev.test.browser.simple
  (:require [clojure.test :refer :all]
            [clojure.spec :as s]
            [clojure.spec.test :as t]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.browser.core :refer
             [webdriver-fixture-once webdriver-fixture-each go-route
              on-unauth-home-page?]]
            [clojure.string :as str]
            [sysrev.db.users :refer [delete-user create-user]]))

(use-fixtures :once default-fixture webdriver-fixture-once)
(use-fixtures :each webdriver-fixture-each)

(deftest home-page-loads
  (go-route "/")
  (is (on-unauth-home-page?)))

(deftest unauthorized-pages-load
  (let [paths ["/project"
               "/user"
               "/project/labels"
               "/project/predict"
               "/project/classify"
               "/select-project"]]
    (doseq [path paths]
      (go-route path)
      (is (on-unauth-home-page?)
          (format "Invalid content on path '%s'" path)))))

(deftest invalid-route-fails
  (let [paths ["/x"]]
    (doseq [path paths]
      (go-route path)
      (is (not (on-unauth-home-page?))
          (format "Invalid path should not load normal content: '%s'" path)))))
