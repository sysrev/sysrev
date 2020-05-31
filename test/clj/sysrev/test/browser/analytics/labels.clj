(ns sysrev.test.browser.analytics.labels
  (:require
    [clojure.tools.logging :as log]
    [clojure.test :refer [is use-fixtures]]
    [sysrev.test.core :as test :refer [default-fixture]]
    [sysrev.test.browser.core :as b :refer [deftest-browser]]
    [sysrev.test.browser.xpath :as x :refer [xpath]]
    [sysrev.test.browser.navigate :as nav]
    [sysrev.test.browser.pubmed :as pm]
    [sysrev.test.browser.stripe :as stripe]
    [sysrev.test.browser.plans :as plans]
    [clj-webdriver.taxi :as taxi]
    [sysrev.test.browser.review-articles :as ra]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

; test concordance with labeled articles
(deftest-browser label-counts (and (test/db-connected?) (not (test/remote-test?))) test-user []
  (do
    ; log in user 1
    (nav/log-in (:email test-user))
    ; subscribe to unlimited
    (plans/user-subscribe-to-unlimited (:email test-user))
    ; create project
    (nav/new-project "label count test")
    (let [project-id (b/current-project-id)
          user-2 (b/create-test-user :email "zoom@zoomers.com" :password "choochoo" :project-id project-id)]
      ; import some articles
      (pm/import-pubmed-search-via-db "foo bar")
      ; label the articles
      (dotimes [_ 3]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ; log in user-2
      (nav/log-out)
      (nav/log-in (:email user-2) "choochoo")
      ; go to project
      (nav/open-project "Simple Test")
      ;label the articles
      (dotimes [_ 3]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ; log out user-2 log in test-user
      (nav/log-out)
      (nav/log-in (:email test-user))
      (nav/open-project "label count test")
      ; go to analytics/labels
      (nav/go-project-route "/analytics/labels")
      (b/wait-until-displayed "h2#answer-count")
      (is (->> (taxi/element "h2#answer-count")
               (taxi/text)
               (= "Label Counts - 3")))
      ))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)
               (is (b/exists? "div#login-register-panel"))))