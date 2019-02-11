(ns sysrev.test.browser.label-settings
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
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(let [project-id (atom nil)
      test-users (mapv #(str "user" % "@fake.com") [1 2 3])
      [user1 user2 user3] test-users
      project-name "Label Consensus Test"
      switch-user (fn [email]
                    (nav/log-out)
                    (nav/log-in email)
                    (nav/go-project-route "/review" @project-id))
      label-def-1 {:value-type "categorical"
                   :short-label "Test Label 1"
                   :question "Is it?"
                   :definition
                   {:all-values ["One" "Two" "Three"]
                    :inclusion-values ["One" "Bar"]
                    :multi? false}
                   :required false}
      all-defs [review/include-label-definition label-def-1]
      lvalues-1 [(-> {:value true}
                     (merge review/include-label-definition))
                 (-> {:value "One"}
                     (merge label-def-1))]
      lvalues-2 [(-> {:value true}
                     (merge review/include-label-definition))
                 (-> {:value "Two"}
                     (merge label-def-1))]]
  (deftest-browser label-consensus-test
    (when (test/db-connected?)
      (nav/log-in)
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (import/import-pmid-vector
       @project-id {:pmids [25706626 #_ 25215519 #_ 23790141]}
       {:use-future? false})
      (nav/go-project-route "/manage")  ; test "Manage" button
      (nav/go-project-route "/labels/edit")
      ;; create a categorical label
      (define/define-label label-def-1)
      ;; there is a new categorical label
      (is (b/exists? (x/match-text "span" (:short-label label-def-1))))
      ;; create users
      (doseq [email test-users]
        (b/create-test-user :email email :project-id @project-id))
      ;; review article from user1
      (switch-user user1)
      (nav/go-project-route "/review")
      (review/set-article-answers lvalues-1)
      ;; review article from user2 (different categorical answer)
      (switch-user user2)
      (nav/go-project-route "/review")
      (review/set-article-answers lvalues-2)
      (is (b/exists? ".no-review-articles")))

    :cleanup
    (when (test/db-connected?)
      (project/delete-project @project-id)
      ;; delete test users
      (doseq [email test-users]
        (b/delete-test-user :email email)))))
