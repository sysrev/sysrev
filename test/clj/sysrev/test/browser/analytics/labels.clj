(ns sysrev.test.browser.analytics.labels
  (:require [clojure.test :refer [use-fixtures]]
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
   user-2 (b/create-test-user :email "zoom@zoomers.com" :password "choochoo")
   n-articles (count (pm/test-search-pmids "foo bar"))]
  (do (nav/log-in (:email test-user))
      (plans/user-subscribe-to-unlimited (:email test-user))
      (nav/new-project project-name)
      (add-project-member (b/current-project-id) (:user-id user-2))
      (pm/import-pubmed-search-via-db "foo bar")
      (dotimes [_ n-articles] (ra/set-article-answers [(include-label true)]))
      (nav/log-in (:email user-2) (:password user-2))
      (nav/open-project project-name)
      (dotimes [_ n-articles] (ra/set-article-answers [(include-label true)]))
      (nav/log-in (:email test-user))
      (nav/open-project project-name)
      (nav/go-project-route "/analytics/labels")
      (b/text-is? "p#answer-count" (format "%d articles with %d answers total"
                                           n-articles (* 2 n-articles))))
  :cleanup (doseq [{:keys [email]} [test-user user-2]]
             (b/cleanup-test-user! :email email)))
