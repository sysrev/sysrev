(ns sysrev.test.browser.analytics.labels
  (:require [clojure.test :refer [use-fixtures]]
            [clj-webdriver.taxi :as taxi]
            [sysrev.project.member :refer [add-project-member]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.review-articles :as ra]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;;; test concordance with labeled articles
(deftest-browser label-counts
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "label count test"
   include-label #(merge ra/include-label-definition {:value %})
   user-2 (b/create-test-user :email "zoom@zoomers.com" :password "choochoo")]
  (do (nav/log-in (:email test-user))
      (plans/user-subscribe-to-unlimited (:email test-user))
      (nav/new-project project-name)
      (add-project-member (b/current-project-id) (:user-id user-2))
      (pm/import-pubmed-search-via-db "foo bar")
      (dotimes [_ 6] (ra/set-article-answers [(include-label true)]))
      (nav/log-in (:email user-2) (:password user-2))
      (nav/open-project project-name)
      (dotimes [_ 6] (ra/set-article-answers [(include-label true)]))
      (nav/log-in (:email test-user))
      (nav/open-project project-name)
      (nav/go-project-route "/analytics/labels")
      (b/text-is? "h4#answer-count" "6 articles with 12 answers total"))
  :cleanup (doseq [{:keys [email]} [test-user user-2]]
             (b/cleanup-test-user! :email email)))
