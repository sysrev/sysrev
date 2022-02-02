(ns sysrev.test.e2e.pubmed-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.formats.pubmed :as pubmed]
   [sysrev.project.article-list :as article-list]
   [sysrev.project.member :as member]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as e-project]))

(def q-search-input {:css "#import-articles form#search-bar input[type=text]"})
(def q-search-submit {:css "#import-articles form#search-bar button[type=submit]"})

(defn num-pages [driver]
  (let [q {:css ".pubmed-search-results .list-pager-nav .page-number"}]
    (ea/wait-visible driver q)
    (some->> (ea/get-element-text driver q)
             (re-matches #"(.|\s)*of (\d*).*")
             last
             Long/parseLong)))

(defn page-num [driver]
  (let [q [{:css ".pubmed-search-results .list-pager-nav"}
           {:fn/has-class :page-number
            :tag :input}]]
    (ea/wait-visible driver q)
    (some-> driver
            (ea/get-element-value q) Long/parseLong)))

(deftest ^:optional test-search
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (testing "Pubmed search works"
      (e/go test-resources "/pubmed-search")
      (doto driver
        (et/is-wait-visible {:id :pubmed-search :fn/has-class :panel})
        (et/is-fill-visible [:pubmed-search :search-bar {:tag :input}] "foo bar")
        (et/is-click-visible [:pubmed-search :search-bar {:fn/has-text "Search"}])))
    (testing "Search shows the correct number of articles"
      (let [search-ct (:count (pubmed/get-search-query-response "foo bar" 1))]
        (et/is-wait-pred
         #(= search-ct
             (count (ea/query-all driver {:css ".pubmed-search-results .pubmed-article"}))))))
    (testing "Search with no results displays properly"
      (doto driver
        (et/clear [:pubmed-search :search-bar {:tag :input}])
        (et/is-fill-visible [:pubmed-search :search-bar {:tag :input}] "foo bar baz qux quux")
        (et/is-click-visible [:pubmed-search :search-bar {:fn/has-text "Search"}])
        (et/is-wait-visible {:fn/has-text "No documents match your search terms"}))
      (et/is-wait-pred
       #(zero?
         (count (ea/query-all driver {:css ".pubmed-search-results .pubmed-article"})))))
    (testing "Pagination works"
      (doto driver
        (et/clear [:pubmed-search :search-bar {:tag :input}])
        (et/is-fill-visible [:pubmed-search :search-bar {:tag :input}] "dangerous statistics three")
        (et/is-click-visible [:pubmed-search :search-bar {:fn/has-text "Search"}])
        (do (et/is-wait-pred #(= 1 (page-num driver))))
        (et/is-wait-visible {:css ".pubmed-search-results .nav-first.disabled"}
                            {}
                            "First page button is disabled")
        (et/is-wait-visible [{:css ".pubmed-search-results .nav-prev-next"}
                             {:fn/has-class :disabled
                              :fn/has-text "Previous"}]
                            {}
                            "Previous page button is disabled on the first page")
        (et/is-click-visible [{:css ".pubmed-search-results .nav-prev-next"}
                              {:fn/has-text "Next"}])
        (do (et/is-wait-pred #(= 2 (page-num driver))))
        (et/is-click-visible {:css ".pubmed-search-results .nav-last"})
        (et/is-wait-visible [{:css ".pubmed-search-results .nav-prev-next"}
                             {:fn/has-class :disabled
                              :fn/has-text "Next"}]
                            {}
                            "Next page button is disabled on the last page"))
      (let [pages (num-pages driver)]
        (et/is-wait-pred #(= pages (page-num driver)))
        (et/is-click-visible driver [{:css ".pubmed-search-results .nav-prev-next"}
                                     {:fn/has-text "Previous"}])
        (et/is-wait-pred #(= (dec pages) (page-num driver)))
        (et/is-click-visible driver {:css ".pubmed-search-results .nav-first"})
        (et/is-wait-pred #(= 1 (page-num driver)))))))

(deftest ^:e2e test-import
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources "Test import-pubmed-sources")]
      (member/add-project-member project-id user-id :permissions ["owner" "admin" "member"])
      (e/go-project test-resources project-id "/add-articles")
      (testing "Can import PubMed articles to a project"
        (doto driver
          (et/is-click-visible [:import-articles {:fn/text "PubMed"}])
          (et/is-fill-visible q-search-input "foo bar")
          (et/is-click-visible q-search-submit)
          e/wait-until-loading-completes
          (et/is-click-visible :import-articles-pubmed)
          e/wait-until-loading-completes))
      (et/is-wait-pred #(seq (article-list/project-article-sources project-id)))
      (is (= #{"9656183" "19505094" "22716928" "23790141"
               "25215519" "25706626" "32891636" "33222245"}
             (->> (test/execute!
                   system
                   {:select :external-id
                    :from :article-data
                    :join [:article [:= :article.article-data-id :article-data.article-data-id]]
                    :where [:in :article-id (keys (article-list/project-article-sources project-id))]})
                  (map :article-data/external-id)
                  set))))))
