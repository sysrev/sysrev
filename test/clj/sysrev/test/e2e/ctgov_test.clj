(ns sysrev.test.e2e.ctgov-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.fixtures.interface :as fixtures]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e test-ctgov-search-import
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (fixtures/load-fixtures! (:postgres system))
    (account/log-in test-resources {:email "test_user_1@insilica.co"
                                    :password "override"})
    (let [article-title "Single Ascending Dose Study of SAR443820 in Healthy Adult Chinese and Japanese Female and Male Participants"]
      (testing "Searching and importing articles works"
        (doto driver
          (ea/go (e/absolute-url system "/u/1000001/p/1200001/add-articles"))
          (et/click-visible [{:id :enable-import}])
          (et/click-visible [{:id :import-articles}
                             {:fn/has-text "ClinicalTrials"}])
          (et/is-wait-visible [{:fn/has-class "ctgov-search"} {:tag :input}])
          (ea/fill-human [{:fn/has-class "ctgov-search"} {:tag :input}]
                         "\"single oral dose\"")
          (et/is-click-visible [{:fn/has-class "ctgov-search"}
                                {:fn/has-class "button" :fn/has-text "Search"}])
          (et/is-wait-visible {:fn/has-text "Found 1 article"})
          (et/is-click-visible {:fn/has-class "button" :fn/has-text "Import"})
          (et/is-wait-visible {:fn/has-class "project-sources-list"})))
      (testing "Imported articles can be searched for in the project"
        (doto driver
          (ea/go (e/absolute-url system "/u/1000001/p/1200001/articles"))
          (et/is-fill-visible {:id :article-search} article-title)
          (et/is-wait-visible {:fn/has-class "article-title"
                               :fn/has-text article-title}))))))
