(ns sysrev.test.browser.analytics
  (:require [clojure.test :refer [use-fixtures]]
            [clj-webdriver.taxi :as taxi]
            [sysrev.project.member :refer [add-project-member]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.review-articles :as ra]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;;; test analytics privileges for basic and unlimited users
(deftest-browser analytics-permissions
  (and (test/db-connected?) (not (test/remote-test?)))
  test-user
  [project-name "analytics-permissions"]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (pm/import-pubmed-search-via-db "foo bar")
      (b/click (xpath "//a[contains(@href,'analytics/concordance')]"))
      ;; analytics panel
      (b/displayed? "div#project_project_analytics")
      (b/displayed? "div#paywall")
      (plans/user-subscribe-to-unlimited (:email test-user))
      (nav/open-project project-name)
      (b/click (xpath "//a[contains(@href,'analytics/concordance')]"))
      ;; empty concordance div should be visible
      (b/displayed? "div#no-data-concordance")))

;;; test concordance with labeled articles
(deftest-browser concordance-generation
  (and (test/db-connected?) (not (test/remote-test?)))
  test-user
  [project-name "concordance-generation"
   include-value #(merge ra/include-label-definition {:value %})
   user-2 (b/create-test-user :email "zoom@zoomers.com" :password "choochoo")]
  (do (nav/log-in (:email test-user))
      (plans/user-subscribe-to-unlimited (:email test-user))
      (nav/new-project project-name)
      (add-project-member (b/current-project-id) (:user-id user-2))
      (pm/import-pubmed-search-via-db "foo bar")
      (dotimes [_ 6] (ra/set-article-answers [(include-value true)]))
      (nav/log-in (:email user-2) (:password user-2))
      (nav/open-project project-name)
      (dotimes [_ 6] (ra/set-article-answers [(include-value true)]))
      (nav/log-in (:email test-user))
      (nav/open-project project-name)
      (nav/go-project-route "/analytics/concordance")
      (b/is-soon (= (taxi/text "h2#overall-concordance")
                    "Concordance 100.0%"))))
