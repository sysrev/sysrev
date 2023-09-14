(ns sysrev.test.e2e.pubmed-test
  (:require [clojure.test :refer :all]
            [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.project.article-list :as article-list]
            [sysrev.project.member :as member]
            [sysrev.test.core :as test]
            [sysrev.test.e2e.account :as account]
            [sysrev.test.e2e.core :as e]
            [sysrev.test.e2e.project :as e-project]))

(def q-search-input {:css "#import-articles form#search-bar input[type=text]"})
(def q-search-submit {:css "#import-articles form#search-bar button[type=submit]"})

(deftest ^:optional test-import
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources "import-pubmed-sources")]
      (member/add-project-member project-id user-id :permissions ["owner" "admin" "member"])
      (e/go-project test-resources project-id "/add-articles")
      (testing "Can import PubMed articles to a project"
        (ea/with-wait-timeout 15
          (doto driver
            (et/is-click-visible [:import-articles {:fn/text "PubMed"}])
            (et/is-fill-visible q-search-input "foo bar")
            (et/is-click-visible q-search-submit)
            e/wait-until-loading-completes
            (et/is-click-visible :import-articles-pubmed)
            e/wait-until-loading-completes)))
      (et/is-wait-pred #(seq (article-list/project-article-sources project-id)))
      (is (every? (->> (test/execute!
                        system
                        {:select :external-id
                         :from :article-data
                         :join [:article [:= :article.article-data-id :article-data.article-data-id]]
                         :where [:in :article-id (keys (article-list/project-article-sources project-id))]})
                       (map :article-data/external-id)
                       set)
                  #{"9656183" "19505094" "22716928" "23790141"
                    "25215519" "25706626" "32891636" "33222245"})))))
