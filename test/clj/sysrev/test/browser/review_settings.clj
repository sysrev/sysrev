(ns sysrev.test.browser.review-settings
  (:require [clojure.test :refer [is use-fixtures]]
            [sysrev.project.core :as project]
            [sysrev.project.member :refer [add-project-member]]
            [sysrev.source.import :as import]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser unlimited-reviews
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-id (atom nil)
   test-users (mapv #(b/create-test-user :email %)
                    (mapv #(str "user" % "@fake.com") [1 2 3]))
   [user1 user2 user3] test-users
   project-name "Unlimited Reviews Test"
   label-definitions [review/include-label-definition]
   review-n-articles #(review/randomly-review-n-articles % label-definitions)
   switch-user (fn [{:keys [email]}]
                 (nav/log-in email)
                 (nav/go-project-route "/add-articles" :project-id @project-id)
                 (nav/go-project-route "/review"))]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (import/import-pmid-vector
       {:web-server (:web-server @test/*test-system*)}
       @project-id {:pmids [25706626 25215519 23790141]}
       {:use-future? false})
      (nav/go-project-route "/settings")
      (b/click "#unlimited-reviews_true")
      (b/click ".project-options button.save-changes")
      ;; create users
      (doseq [{:keys [user-id]} test-users]
        (add-project-member @project-id user-id))
      (switch-user user1)
      (review-n-articles 2)
      (is (b/exists? ".ui.segments.article-info"))
      (switch-user user2)
      (review-n-articles 3)
      (is (b/exists? ".no-review-articles"))
      (switch-user user3)
      (review-n-articles 2)
      (is (b/exists? ".ui.segments.article-info"))
      (review-n-articles 1)
      (is (b/exists? ".no-review-articles"))
      (switch-user user1)
      ;; user1 can review this article even though already has 2 reviews
      (is (b/exists? ".ui.segments.article-info"))
      (project/change-project-setting @project-id :unlimited-reviews false)
      (b/init-route (str "/p/" @project-id "/add-articles"))
      (nav/go-project-route "/review")
      ;; can not review after unlimited setting disabled
      (is (b/exists? ".no-review-articles"))
      (project/change-project-setting @project-id :unlimited-reviews true)
      ;; re-enable setting, finish reviewing last article
      (b/init-route (str "/p/" @project-id "/add-articles"))
      (nav/go-project-route "/review")
      (is (b/exists? ".ui.segments.article-info"))
      (review-n-articles 1)
      (is (b/exists? ".no-review-articles"))
      ;; test that some label-related pages load
      (nav/go-project-route "")
      (is (b/exists? "#project_project_overview"))
      (nav/go-project-route "/articles")
      (is (b/exists? ".article-list-view .list-pager")))
  :cleanup (do (some-> @project-id (project/delete-project))
               (doseq [{:keys [email]} test-users]
                 (b/cleanup-test-user! :email email))))
