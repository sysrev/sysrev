(ns sysrev.test.browser.project_payments
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [sysrev.test.browser.core :as browser :refer [deftest-browser]]
            [sysrev.test.browser.create-project :as create-project]
            [sysrev.test.browser.navigate :refer [log-in log-out]]
            [sysrev.test.browser.project-compensation :as project-compensation]
            [sysrev.test.browser.review-articles :as review]
            [sysrev.test.core :refer
             [default-fixture full-tests? test-profile? add-test-label]]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

#_ (deftest-browser support-project-and-compensate-reviewers
  (when (browser/db-connected?)
    (let [project-name "SysRev Paid Reviewers"
          compensation-amount 123
          search-term-source "foo create"
          test-user {:name "foo"
                     :email "foo@bar.com"
                     :password "foobar"
                     :n-articles 3}
          label-definitions
          [(merge review/include-label-definition
                  {:all-values [true false]})
           (merge review/categorical-label-definition
                     {:all-values
                      (get-in review/categorical-label-definition
                              [:definition :all-values])})
           (merge review/boolean-label-definition
                  {:all-values [true false]})]]
      (try
        (log-in)
        ;; create the project
        (create-project/create-project project-name)
        (create-project/add-articles-from-search-term search-term-source)
        ;; create boolean label
        (browser/click review/label-definitions-tab)
        (browser/click review/add-boolean-label-button)
        (review/set-label-values "//div[contains(@id,'new-label-')]" review/boolean-label-definition)
        (review/save-label)
        ;; create categorical label
        (browser/click review/add-categorical-label-button)
        (review/set-label-values "//div[contains(@id,'new-label-')]" review/categorical-label-definition)
        (review/save-label)
        ;; set the compensations
        (project-compensation/create-compensation compensation-amount)
        ;; create a user
        (browser/create-test-user :email (:email test-user) :password (:password test-user)
                                  :project-id (browser/current-project-id))))))
