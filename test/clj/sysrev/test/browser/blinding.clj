(ns sysrev.test.browser.blinding
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.tools.logging :as log]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.source.import :as import]
            [sysrev.project.member :refer [add-project-member]]
            [sysrev.test.browser.user-profiles :refer [change-project-label-blinding]]))

(deftest-browser label-blinding
                 (and (test/db-connected?) (not (test/remote-test?))) test-user
                 [test-users (mapv #(b/create-test-user :email %) (mapv #(str "user" % "@fake.com") [1 2]))
                  [user1 user2] test-users
                  project-name "Sysrev Browser Test (label blinding)"
                  click-project-link #(do (log/infof "loading project %s" (pr-str %))
                                          (b/click (xpath "//a[contains(text(),'" % "')]")))
                  project-id (atom nil)]
  (do
    (nav/log-in (:email user1))
    ;; subscribe to plans
    (plans/user-subscribe-to-unlimited (:email user1))
    ;; create a project, populate with articles
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    ;; set the project setting for label blinding to true
    (change-project-label-blinding true)
    (import/import-pmid-vector
      @project-id {:pmids [25706626 25215519 23790141]}
      {:use-future? false})
    ;; do some work
    (click-project-link project-name)
    (b/click (x/project-menu-item :review))
    ;; review all articles
    (dotimes [_ 3]
      (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
    ;; log in user2
    (nav/log-in (:email user2))
    ;; go to articles page
    (b/init-route (str "/p/" @project-id "/articles"))
    ;; review times are visible
    (assert (b/exists? ".ui.updated-time"))
    ;; check that no answers are visible
    (assert (not (taxi/exists? "div.answer-cell")))
    ;; go to an article
    (b/click "div.article-list-article")
    ;; check that no article labels are visible
    (assert (not (taxi/exists? ".article-labels-view")))
    ;; add user2 as a member and check for blinding again
    (nav/log-in (:email user1))
    (nav/open-project project-name)
    ;; add users to project
    (add-project-member @project-id (:user-id user2))
    (nav/log-in (:email user2))
    ;; go to articles page
    (b/init-route (str "/p/" @project-id "/articles"))
    ;; review times are visible
    (assert (b/exists? ".ui.updated-time"))
    ;; check that no answers are visible
    (assert (not (taxi/exists? "div.answer-cell")))
    ;; go to an article
    (b/click "div.article-list-article")
    ;; check that no article labels are visible
    (assert (not (taxi/exists? ".article-labels-view")))
    (taxi/take-screenshot :file "/tmp/tom.png")
    ))

