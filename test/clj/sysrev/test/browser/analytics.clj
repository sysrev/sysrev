(ns sysrev.test.browser.analytics
  (:require [clojure.test :refer [is use-fixtures]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.plans :as plans]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.browser.review-articles :as ra]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn root-panel-exists? []
  (b/is-soon (some #(nav/panel-exists? % :wait? false)
                   [[:project :project :overview]
                    [:project :project :add-articles]])))

;;; test analytics privileges for basic and unlimited users
(deftest-browser analytics-permissions
  (and (test/db-connected?) (not (test/remote-test?)))
  test-user []
  (do (nav/log-in (:email test-user))
      (nav/new-project "Simple Test")
      ;; import some articles
      (pm/import-pubmed-search-via-db "foo bar")
      ;; go to concordance
      (b/click (xpath "//a[contains(@href,'analytics/concordance')]"))
      ;; check for analytics nav-panel
      (is (taxi/exists? "div#project_project_analytics"))
      ;; check if #paywall div is visible
      (b/wait-until-displayed "div#paywall")
      (is (taxi/exists? "div#paywall"))
      ;; get an Unlimited_User account
      (plans/user-subscribe-to-unlimited (:email test-user))
      ;; come back to analytics page
      (nav/open-project "Simple Test")
      (b/click (xpath "//a[contains(@href,'analytics/concordance')]"))
      ;; empty concordance div should be visible
      (b/wait-until-displayed "div#no-data-concordance")
      (is (taxi/exists? "div#no-data-concordance")))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)
               (is (b/exists? "div#login-register-panel"))))

;;; test concordance with labeled articles
(deftest-browser concordance-generation
  (and (test/db-connected?) (not (test/remote-test?)))
  test-user []
  (do (nav/log-in (:email test-user))
      (plans/user-subscribe-to-unlimited (:email test-user))
      (nav/new-project "Simple Test")
      (let [project-id (b/current-project-id)
            user-2 (b/create-test-user :email "zoom@zoomers.com" :password "choochoo"
                                       :project-id project-id)]
        (pm/import-pubmed-search-via-db "foo bar")
        (dotimes [_ 6]
          (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
        (nav/log-in (:email user-2) "choochoo")
        (nav/open-project "Simple Test")
        (dotimes [_ 6]
          (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
        (nav/log-in (:email test-user))
        (nav/open-project "Simple Test")
        (nav/go-project-route "/analytics/concordance")
        (b/wait-until-displayed "h2#overall-concordance")
        (is (= (taxi/text "h2#overall-concordance")
               "Concordance 100.0%"))))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)
               (is (b/exists? "div#login-register-panel"))))
