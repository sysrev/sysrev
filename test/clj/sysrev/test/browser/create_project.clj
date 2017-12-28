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
;; if you close Chrome, you will also need to take care of the active-webdriver atom
;; by running (browser/stop-webdriver)

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

(defn max-pages
  "Return max number of pages"
  []
  (let [pages-query {:xpath "//form/div[contains(text(),'Page')]"}]
    (browser/wait-until-exists pages-query)
    (->> (taxi/text (taxi/find-element pages-query))
         (re-matches #"Page of (\d*)")
         second
         clojure.core/read-string)))

(defn get-current-page-number
  []
  (clojure.core/read-string (taxi/value (taxi/find-element {:xpath "//div[@class='item']/form/div/input[@type='text']"}))))

(defn search-term-count-matches?
  "Does the search-term result in the browser match the remote call?"
  [search-term]
  (search-for search-term)
  (is (= (:count (pubmed/get-search-query-response search-term 1))
         (search-count))))

(defn click-pager
  "Given a nav string, click the link in the pager corresponding to that position"
  [nav]
  (let [query {:xpath (str "//a[contains(@class,'page-link') and contains(text(),'" nav "')]")}]
    (browser/wait-until-exists query)
    (taxi/click (taxi/find-element query))))

(defn disabled-pager-link?
  "Given a nav string, check to see if that pager link is disabled"
  [nav]
  (let [query {:xpath (str "//a[contains(@class,'page-link') and contains(text(),'" nav "')]")}]
    (browser/wait-until-exists query)
    (boolean (re-matches #".*disabled" (taxi/attribute query :class)))))

(deftest pubmed-search
  (log-in)
  (browser/go-route "/create-project")
  (browser/wait-until-panel-exists [:create-project])
  (testing "Various search terms will yield the correct pmid count"
    (search-term-count-matches? "foo")
    (search-term-count-matches? "foo bar"))
  (testing "A search term with no documents"
    (search-for "foo bar baz qux quux")
    (browser/wait-until-exists {:xpath "//h3[contains(text(),'No documents match your search terms')]"})
    (is (taxi/exists? {:xpath "//h3[contains(text(),'No documents match your search terms')]"})))
  (testing "Pager works properly"
    (search-for "foo")
    (is (disabled-pager-link? "First"))
    (is (disabled-pager-link? "Prev"))
    ;; Go to next page
    (click-pager "Next")
    (is (= 2
           (get-current-page-number)))
    ;; Go to last page
    (click-pager "Last")
    (is (disabled-pager-link? "Next"))
    (is (disabled-pager-link? "Last"))
    (is (= (max-pages)
           (get-current-page-number)))
    ;; Go back one page
    (click-pager "Prev")
    (is (= (- (max-pages) 1)
           (get-current-page-number)))
    ;; Go to first page
    (click-pager "First")
    (is (= 1)
        (get-current-page-number))))
