(ns sysrev.test.browser.review-settings
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.source.import :as import]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review]
            [sysrev.test.browser.pubmed :as pm]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser unlimited-reviews
  (test/db-connected?)
  [project-id (atom nil)
   test-users (mapv #(str "user" % "@fake.com") [1 2 3])
   [user1 user2 user3] test-users
   project-name "Unlimited Reviews Test"
   label-definitions
   [review/include-label-definition]
   review-articles
   (fn [n-articles]
     (review/randomly-review-n-articles n-articles label-definitions))
   switch-user (fn [email]
                 (nav/log-out)
                 (nav/log-in email)
                 (nav/go-project-route "/review" @project-id))]
  (do (nav/log-in)
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (import/import-pmid-vector
       @project-id {:pmids [25706626 25215519 23790141]}
       {:use-future? false})
      (nav/go-project-route "/settings")
      (b/click "#unlimited-reviews_true")
      (b/click ".project-options button.save-changes")
      ;; create users
      (doseq [email test-users]
        (b/create-test-user :email email :project-id @project-id))
      (switch-user user1)
      (review-articles 2)
      (is (b/exists? ".ui.segments.article-info"))
      (switch-user user2)
      (review-articles 3)
      (is (b/exists? ".no-review-articles"))
      (switch-user user3)
      (review-articles 2)
      (is (b/exists? ".ui.segments.article-info"))
      (review-articles 1)
      (is (b/exists? ".no-review-articles"))
      (switch-user user1)
      ;; user1 can review this article even though already has 2 reviews
      (is (b/exists? ".ui.segments.article-info"))
      (project/change-project-setting @project-id :unlimited-reviews false)
      (nav/init-route (str "/p/" @project-id "/review"))
      ;; can not review after unlimited setting disabled
      (is (b/exists? ".no-review-articles"))
      (project/change-project-setting @project-id :unlimited-reviews true)
      ;; re-enable setting, finish reviewing last article
      (nav/init-route (str "/p/" @project-id "/review"))
      (is (b/exists? ".ui.segments.article-info"))
      (review-articles 1)
      (is (b/exists? ".no-review-articles"))
      ;; test that some label-related pages load
      (nav/go-project-route "")
      (is (b/exists? "#project_project_overview"))
      (nav/go-project-route "/articles")
      (is (b/exists? ".article-list-view .list-pager")))

  :cleanup
  (do (project/delete-project @project-id)
      (doseq [email test-users]
        (b/delete-test-user :email email))))
