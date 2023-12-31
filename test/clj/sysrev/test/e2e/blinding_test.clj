(ns sysrev.test.e2e.blinding-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [sysrev.api :as api]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.project.member :as member]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test]
            [sysrev.test.e2e.account :as account]
            [sysrev.test.e2e.core :as e]
            [sysrev.test.e2e.labels :as labels]))

(defn change-project-label-blinding!
  "Change label blinding setting for current project."
  [{:keys [driver] :as test-resources} project-id blinding?]
  (let [q (str "button#blind-reviewers_" (boolean blinding?))]
    (log/infof "changing label blinding to %s" (boolean blinding?))
    (e/go-project test-resources project-id "/settings")
    (doto driver
      (et/click-visible {:css (str q ":" e/not-disabled ":" e/not-active)})
      (et/click-visible {:css "div.project-options button.save-changes"})
      e/wait-until-loading-completes)))

(deftest ^:e2e label-blinding
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [sr-context]} system
          user1 (test/create-test-user system)
          user2 (test/create-test-user system)
          {:keys [project]} (api/create-project-for-user!
                             (:sr-context system)
                             "Sysrev-Browser-Test-label-blinding"
                             (:user-id user1)
                             true)
          {:keys [project-id]} project]
      (test/change-user-plan! system (:user-id user1) "Unlimited_Org_Annual_free")
      (doto test-resources
        (account/log-in user1)
        ;; set the project setting for label blinding to true
        (change-project-label-blinding! project-id true))
      ;; import pubmed articles
      (src/import-source
       sr-context :pmid-vector project-id
       {:pmids [25706626 25215519 23790141]}
       {:use-future? false})
      ;; review all articles
      (e/go-project test-resources project-id "/review")
      (dotimes [_ 3]
        (doto driver
          (labels/set-label-answer! (assoc labels/include-label-definition :value true))
          (et/is-click-visible {:css ".button.save-labels"})
          e/wait-until-loading-completes))
      ;; log in as user2
      (doto test-resources
        account/log-out
        (account/log-in user2))
      ;; go to articles page
      (e/go-project test-resources project-id "/articles")
      (doto driver
        ;; review times are visible
        (et/is-wait-exists {:css ".ui.updated-time"})
        ;; check that no answers are visible
        (et/is-not-exists? {:css "div.answer-cell"})
        ;; go to an article
        (et/is-click-visible {:css "div.article-list-article"})
        e/wait-until-loading-completes
        ;; check that no article labels are visible
        (et/is-not-exists? {:css ".article-labels-view"}))
      ;; add user2 as a member and check for blinding again
      (member/add-project-member project-id (:user-id user2))
      ;; go to articles page
      (e/go-project test-resources project-id "/articles")
      (doto driver
        e/refresh
        ;; review times are visible
        (et/is-wait-exists {:css ".ui.updated-time"})
        ;; check that no answers are visible
        (et/is-not-exists? {:css "div.answer-cell"})
        ;; go to an article
        (et/is-click-visible {:css "div.article-list-article"})
        e/wait-until-loading-completes
        ;; check that no article labels are visible
        (et/is-not-exists? {:css ".article-labels-view"})))))
