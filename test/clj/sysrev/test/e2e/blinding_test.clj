(ns sysrev.test.e2e.blinding-test
  (:require
   [clojure.test :refer :all]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [sysrev.api :as api]
   [sysrev.project.member :as member]
   [sysrev.source.import :as import]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.labels :as labels]))

(defn change-project-label-blinding!
  "Change label blinding setting for current project."
  [{:keys [driver system]} project-id blinding?]
  (let [q (str "button#blind-reviewers_" (boolean blinding?))]
    (log/infof "changing label blinding to %s" (boolean blinding?))
    (doto driver
      (ea/go (e/absolute-url system (str "/p/" project-id "/settings")))
      (e/click-visible {:css (str q ":" e/not-disabled ":" e/not-active)})
      (e/click-visible {:css "div.project-options button.save-changes"})
      e/wait-until-loading-completes)))

(deftest ^:e2e label-blinding
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user1 (test/create-test-user)
          user2 (test/create-test-user)
          {:keys [project]} (api/create-project-for-user!
                             "Sysrev Browser Test (label blinding)"
                             (:user-id user1)
                             true)
          {:keys [project-id]} project]
      (doto test-resources
        (account/change-user-plan! (:user-id user1) "Unlimited_User")
        (account/log-in user1)
        ;; set the project setting for label blinding to true
        (change-project-label-blinding! project-id true))
      ;; import pubmed articles
      (import/import-pmid-vector
       (select-keys system [:web-server])
       project-id
       {:pmids [25706626 25215519 23790141]}
       {:use-future? false})
      ;; review all articles
      (ea/go driver (e/absolute-url system (str "/p/" project-id "/review")))
      (dotimes [_ 3]
        (doto driver
          (labels/set-label-answer! (assoc labels/include-label-definition :value true))
          (e/click-visible {:css ".button.save-labels"})
          e/wait-until-loading-completes))
      ;; log in as user2
      (doto test-resources
        account/log-out
        (account/log-in user2))
      (doto driver
        ;; go to articles page
        (ea/go (e/absolute-url system (str "/p/" project-id "/articles")))
        e/wait-until-loading-completes
        ;; review times are visible
        (ea/wait-exists {:css ".ui.updated-time"})
        ;; check that no answers are visible
        (-> (ea/exists? {:css "div.answer-cell"})
            not is)
        ;; go to an article
        (e/click-visible {:css "div.article-list-article"})
        e/wait-until-loading-completes
        ;; check that no article labels are visible
        (-> (ea/exists? {:css ".article-labels-view"})
            not is))
      ;; add user2 as a member and check for blinding again
      (member/add-project-member project-id (:user-id user2))
      (doto driver
        ;; go to articles page
        (ea/go (e/absolute-url system (str "/p/" project-id "/articles")))
        e/wait-until-loading-completes
        ;; review times are visible
        (ea/wait-exists {:css ".ui.updated-time"})
        ;; check that no answers are visible
        (-> (ea/exists? {:css "div.answer-cell"})
            not is)
        ;; go to an article
        (e/click-visible {:css "div.article-list-article"})
        e/wait-until-loading-completes
        ;; check that no article labels are visible
        (-> (ea/exists? {:css ".article-labels-view"})
            not is)))))
