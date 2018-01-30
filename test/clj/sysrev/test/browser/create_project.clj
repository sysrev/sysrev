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
  (let [search-input {:xpath "//input[contains(@placeholder,'PubMed Search...')]"}
        search-form {:xpath "//form[@id='search-bar']"}]
    (browser/wait-until-exists search-form)
    (browser/wait-until-exists search-input)
    (taxi/clear search-input)
    (taxi/input-text search-input query)
    (taxi/submit search-form)))

(defn search-count
  "Return an integer item count of search results"
  []
  (let [parse-count-string
        (fn [count-string]
          (clojure.core/read-string (second (re-matches #".*of (\d*)" count-string))))
        count-query {:xpath "//h5[@id='items-count']"}]
    (browser/wait-until-exists count-query)
    (parse-count-string (taxi/text (taxi/find-element count-query)))))

(defn max-pages
  "Return max number of pages"
  []
  (let [pages-query {:xpath "//form/div[contains(text(),'Page')]"}]
    (browser/wait-until-exists pages-query)
    (->> (taxi/text (taxi/find-element pages-query))
         (re-matches #"(.|\s)*of (\d*)")
         last
         clojure.core/read-string)))

(defn get-current-page-number
  []
  (->> {:xpath "//input[contains(@class,'search-page-number') and @type='text']"}
       taxi/find-element taxi/value clojure.core/read-string))

(defn search-term-count-matches?
  "Does the search-term result in the browser match the remote call?"
  [search-term]
  (search-for search-term)
  (browser/wait-until-loading-completes)
  (is (= (:count (pubmed/get-search-query-response search-term 1))
         (search-count))))

(defn click-pager
  "Given a nav string, click the link in the pager corresponding to that position"
  [nav]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(text(),'" nav "')]")}]
    (browser/wait-until-exists query)
    (taxi/click (taxi/find-element query))
    (Thread/sleep 250)))

(defn disabled-pager-link?
  "Given a nav string, check to see if that pager link is disabled"
  [nav]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(text(),'" nav "')]")}]
    (browser/wait-until-loading-completes)
    (browser/wait-until-exists query)
    (boolean (re-matches #".*disabled" (taxi/attribute query :class)))))

(deftest pubmed-search
  (log-in)
  (browser/go-route "/pubmed-search")
  (browser/wait-until-panel-exists [:pubmed-search])
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
        (get-current-page-number))
    (log-out)))

(defn delete-current-project
  []
  (browser/go-route "/project/settings")
  (browser/wait-until-exists {:xpath "//h4[contains(text(),'Delete Project')]"})
  (taxi/click {:xpath "//button[contains(text(),'Delete this Project')]"})
  (browser/wait-until-exists {:xpath "//h3[contains(text(),'Create a New Project')]"}))

(deftest create-project-and-import-sources
  (let [project-name "Foo Bar"
        search-term-first "foo bar"
        search-term-second "grault"]
    (log-in)
    ;; create a project
    (browser/go-route "/select-project")
    (browser/wait-until-loading-completes)
    (taxi/input-text {:xpath "//input[@placeholder='Project Name']"} project-name)
    (taxi/click {:xpath "//button[text()='Create']"})
    (browser/wait-until-displayed {:xpath "//span[contains(@class,'project-title')]"})
    ;; was the project actually created?
    (is (.contains (taxi/text {:xpath "//span[contains(@class,'project-title')]"}) project-name))
    ;; add articles from first search term
    (browser/go-route "/project/add-articles")
    (search-for search-term-first)
    (browser/wait-until-displayed {:xpath "//div[contains(@class,'button') and contains(text(),'Import')]"})
    (taxi/click {:xpath "//div[contains(@class,'button') and contains(text(),'Import')]"})
    ;; check that they've loaded
    (browser/wait-until-displayed {:xpath (str "//div[contains(text(),'" search-term-first "')]/ancestor::div[contains(@class,'project-source')]/descendant::div[contains(text(),'articles reviewed')]")})
    (is (taxi/exists? {:xpath (str "//div[contains(text(),'" search-term-first "')]/ancestor::div[contains(@class,'project-source')]/descendant::div[contains(text(),'articles reviewed')]")}))
    ;; check that there is one article source listed
    (taxi/wait-until #(= 1 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]"}))))
    (is (= 1 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]"}))))
    (let [close {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}]
      (when (taxi/exists? close)
        (taxi/click close)))
    ;; add articles from second search term
    (search-for search-term-second)
    (browser/wait-until-displayed {:xpath "//div[contains(@class,'button') and contains(text(),'Import')]"})
    (taxi/click {:xpath "//div[contains(@class,'button') and contains(text(),'Import')]"})
    (browser/wait-until-displayed {:xpath (str "//div[contains(text(),'" search-term-second "')]/ancestor::div[contains(@class,'project-source')]/descendant::div[contains(text(),'articles reviewed')]")})
    ;; check that they've loaded
    (is (taxi/exists? {:xpath (str "//div[contains(text(),'" search-term-second "')]/ancestor::div[contains(@class,'project-source')]/descendant::div[contains(text(),'articles reviewed')]")}))
    ;; check that there is one article source listed
    (taxi/wait-until #(= 2 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"}))))
    (is (= 2 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"}))))
    ;; delete the first source
    (let [delete {:xpath (str "//div[contains(text(),'" search-term-first "')]/ancestor::div[contains(@class,'project-source')]/descendant::div[contains(@class,'button') and contains(text(),'Delete')]")}]
      (browser/wait-until-exists delete)
      (taxi/click delete))
    ;; count is back down to one
    (taxi/wait-until #(= 1 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"}))))
    (is (= 1 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"}))))
    ;; delete the second source
    (let [delete {:xpath (str "//div[contains(text(),'" search-term-second "')]/ancestor::div[contains(@class,'project-source')]/descendant::div[contains(@class,'button') and contains(text(),'Delete')]")}]
      (browser/wait-until-exists delete)
      (taxi/click delete))
    ;; count is down to zero
    (taxi/wait-until #(= 0 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"}))))
    (is (= 0 (count (taxi/find-elements {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"}))))
    (delete-current-project)
    (log-out)))
