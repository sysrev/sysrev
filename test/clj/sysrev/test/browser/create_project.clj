(ns sysrev.test.browser.create-project
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.test.browser.core :as browser]
            [sysrev.test.browser.navigate :refer [log-in log-out register-user]]))

;; manual tests
;; (browser/start-visual-webdriver)
;; (sysrev.init/start-app) ; start the app, if not already running
;; run some tests
;; (browser/stop-webdriver)

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

(defn search-for
  "Given a string query, enter the search term in the search bar"
  [query]
  (let [search-input (taxi/find-element
                      {:xpath "//input[contains(@placeholder,'PubMed Search...')]"})]
    (taxi/clear search-input)
    (taxi/input-text search-input query)
    (taxi/click (taxi/find-element {:xpath "//i[contains(@class,'search')]"}))))

(defn search-count
  "Return an integer item count of search results"
  []
  (let [parse-count-string
        (fn [count-string]
          (clojure.core/read-string (or (second (re-matches #".*of (\d*)" count-string))
                                        (second (re-matches #"Items: (\d*)" count-string)))))
        count-query {:xpath "//h4[@id='items-count']"}]
    (browser/wait-until-exists count-query)
    (parse-count-string (taxi/text (taxi/find-element count-query)))))

(defn search-term-count-matches?
  "Does the search-term result in the browser match the remote call?"
  [search-term]
  (search-for search-term)
  (is (= (:count (pubmed/get-search-query-response search-term 1))
         (search-count))))

(deftest pubmed-search
  (log-in)
  (browser/go-route "/create-project")
  (browser/wait-until-panel-exists [:create-project])
  (testing "Various search terms will yield the correct pmid count"
    (search-term-count-matches? "foo")
    (search-term-count-matches? "foo bar")))
