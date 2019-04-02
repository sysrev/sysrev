(ns sysrev.test.browser.orgs
  (:require [clojure.test :refer :all]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.core :as test]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)


(deftest-browser create-organization-add-users
  (test/db-connected?)
  [org-name "Foo Bar, Inc."]
  (println "Tests Here")
  ;; test that:
  ;; a person can create a org and they are automatically made owners

  ;; an owner can add a user to the org
  
  ;; an owner can change permissions of a member

  ;; only an owner can change permissions, not a member

  ;; when an org is switched, the correct user list shows up
  ;; a) when creating a new org
  ;; b) when switching between orgs in org view

  :cleanup
  (println "Cleanup Here"))
