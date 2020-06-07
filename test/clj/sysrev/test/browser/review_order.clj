(ns sysrev.test.browser.review-order
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.db.core :as db]
            [sysrev.label.core :as labels]
            [sysrev.project.core :as project]
            [sysrev.user.core :as user :refer [user-self-info]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]
            [sysrev.test.browser.review-articles :as ra]))

; test concordance with labeled articles
(deftest-browser label-counts (and (test/db-connected?) (not (test/remote-test?))) test-user []
  (do
    ; log in user 1
    (nav/log-in (:email test-user))
    ; create project
    (nav/new-project "review order test")
    (let [project-id (b/current-project-id)
          user-2 (b/create-test-user :email "zoom@zoomers.com" :password "choochoo" :project-id project-id)]
      ; import some articles
      (pm/import-pubmed-search-via-db "foo bar")
      ; label 5 articles without confirming
      (dotimes [_ 5]
        (ra/set-article-answers
          [(merge ra/include-label-definition {:value true})]
          :save? false))
      ; log in user-2
      (nav/log-out)
      (nav/log-in (:email user-2) "choochoo")
      ; go to project
      (nav/open-project "review order test")
      ;label the articles
      (dotimes [_ 6]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ; log out user-2 log in test-user
      (nav/log-out)
      (nav/log-in (:email test-user))
      (nav/open-project "review order test")
      ; go to analytics/labels
      (nav/go-project-route "/analytics/labels")
      (b/wait-until-displayed "h4#answer-count")
      (is (->> (taxi/element "h4#answer-count")
               (taxi/text)
               (= "6 articles with 12 answers total")))
      ))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)
               (is (b/exists? "div#login-register-panel"))))