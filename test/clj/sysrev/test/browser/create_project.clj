(ns sysrev.test.browser.create-project
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
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
  (browser/wait-until-exists {:xpath "//button[text()='Yes']"})
  (taxi/click {:xpath "//button[text()='Yes']"})
  (browser/wait-until-exists {:xpath "//h3[contains(text(),'Create a New Project')]"}))

(defn search-term-source-div-xpath
  "Given a search term, return a string of the xpath corresponding to the project-source div"
  [search-term]
  (str "//div[contains(@class,'import-label') and text()='\"" search-term "\"']/ancestor::div[@class='project-source']"))

(defn search-term-delete-xpath
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (str (search-term-source-div-xpath search-term)
       "/descendant::div[contains(@class,'button') and contains(text(),'Delete')]"))

(defn search-term-articles-summary
  "Given a search term, return the map corresponding to the overlap of the articles of the form:
  {:unique-articles <int>
   :reviewed-articles <int>
   :total-articles <int>
   :overlap [{:overlap-count <int>
              :source string}]"
  [search-term]
  (let [[reviewed unique & overlap] (-> {:xpath (str (search-term-source-div-xpath search-term)
                                                     "/descendant::div[contains(@class,'source-description')]")}
                                        taxi/text
                                        (string/split #"\n"))
        [_ reviewed total] (re-matches #"(\d*) of (\d*)(?:\s|\S)*" reviewed)
        overlap-string->overlap-map (fn [overlap-string]
                                      (let [[_ overlap source]
                                            (re-matches #"(\d*) article(?:s)? shared with (.*)" overlap-string)]
                                        (if-not (and (nil? overlap) (nil? source))
                                          {:overlap (read-string overlap) :source source})))
        summary-map {:unique-articles (read-string (second (re-matches #"(\d*) unique article(?:s)?" unique)))
                     :reviewed-articles (read-string reviewed)
                     :total-articles (read-string total)}]
    (if-not (empty? overlap)
      (assoc summary-map :overlap-maps
             (mapv overlap-string->overlap-map overlap))
      summary-map)))

(def import-button-xpath {:xpath "//div[contains(@class,'button') and contains(text(),'Import')]"})

(defn add-articles-from-search-term
  "Create a new source using search-term"
  [search-term]
  ;; add articles from first search term
  (search-for search-term)
  (browser/wait-until-displayed import-button-xpath)
  (taxi/click import-button-xpath)
  ;; check that they've loaded
  (browser/wait-until-displayed {:xpath (str (search-term-source-div-xpath search-term)
                                             "/descendant::div[contains(text(),'reviewed')]")})
  (is (taxi/exists? {:xpath (str (search-term-source-div-xpath search-term)
                                 "/descendant::div[contains(text(),'reviewed')]")})))

(defn delete-search-term-source
  [search-term]
  (let [delete {:xpath (search-term-delete-xpath search-term)}]
    (browser/wait-until-exists delete)
    (taxi/click delete)))

(def project-title-xpath {:xpath "//span[contains(@class,'project-title')]"})
(def article-sources-list-xpath {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]"})
(def project-source-xpath {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"})

(deftest create-project-and-import-sources
  (let [project-name "Foo Bar"
        search-term-first "foo bar"
        search-term-second "grault"
        search-term-third "foo bar Aung"
        search-term-fourth "foo bar Jones"]
    (log-in)
;;; create a project
    (browser/go-route "/select-project")
    (browser/wait-until-loading-completes)
    (taxi/input-text {:xpath "//input[@placeholder='Project Name']"} project-name)
    (taxi/click {:xpath "//button[text()='Create']"})
    (browser/wait-until-displayed project-title-xpath)
    ;; was the project actually created?
    (is (.contains (taxi/text project-title-xpath) project-name))
    (browser/go-route "/project/add-articles")

;;; add sources
    ;; create a new source
    (add-articles-from-search-term search-term-first)
    ;; check that there is one article source listed
    (taxi/wait-until #(= 1 (count (taxi/find-elements article-sources-list-xpath))))
    (is (= 1 (count (taxi/find-elements article-sources-list-xpath))))
    (let [close {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}]
      (when (taxi/exists? close)
        (taxi/click close)))
    ;; add articles from second search term
    (add-articles-from-search-term search-term-second)
    ;; check that there is one article source listed
    (taxi/wait-until #(= 2 (count (taxi/find-elements project-source-xpath))))
    (is (= 2 (count (taxi/find-elements project-source-xpath))))
    ;; check that there is no overlap
    (is (and (nil? (:overlap-maps (search-term-articles-summary search-term-first)))
             (nil? (:overlap-maps (search-term-articles-summary search-term-second)))))
    ;; add articles from third search term
    (add-articles-from-search-term search-term-third)
    ;; search-term-third has no unique article or reviewed articles, only one article and one overlap with "foo bar"
    (is (= {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
            :overlap-maps [{:overlap 1, :source "PubMed Search \"foo bar\""}]}
           (search-term-articles-summary search-term-third)))
    ;; add articles from fourth search term
    (add-articles-from-search-term search-term-fourth)
    ;; search-term-first has 4 unique articles, 0 reviewed articles, 6 total articles, and have two overalaps
    (is (= (search-term-articles-summary search-term-first)
           {:unique-articles 4, :reviewed-articles 0, :total-articles 6,
            :overlap-maps [{:overlap 1, :source "PubMed Search \"foo bar Aung\""} {:overlap 1, :source "PubMed Search \"foo bar Jones\""}]}))

;;; delete sources
    ;; delete the search-term-first source
    (delete-search-term-source search-term-fourth)
    ;; total sources is three
    (taxi/wait-until #(= 3 (count (taxi/find-elements project-source-xpath))))
    (is (= 3 (count (taxi/find-elements project-source-xpath))))
    ;; article summaries are correct
    (is (= (search-term-articles-summary search-term-first)
           {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
            :overlap-maps [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}]}))
    (is (= (search-term-articles-summary search-term-third)
           {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
            :overlap-maps [{:overlap 1, :source "PubMed Search \"foo bar\""}]}))
    ;; delete the search-term-second source
    (delete-search-term-source search-term-second)
    ;; total sources is two
    (taxi/wait-until #(= 2 (count (taxi/find-elements project-source-xpath))))
    (is (= 2 (count (taxi/find-elements project-source-xpath))))
    ;; article summaries are correct
    (is (= (search-term-articles-summary search-term-first)
           {:unique-articles 5, :reviewed-articles 0, :total-articles 6, :overlap-maps [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}]}))
    (is (= (search-term-articles-summary search-term-third)
           {:unique-articles 0, :reviewed-articles 0, :total-articles 1, :overlap-maps [{:overlap 1, :source "PubMed Search \"foo bar\""}]}))
    ;; delete the search-term-third source
    (delete-search-term-source search-term-third)
    ;; total sources is one
    (taxi/wait-until #(= 1 (count (taxi/find-elements project-source-xpath))))
    (is (= 1 (count (taxi/find-elements project-source-xpath))))
    ;; article summaries are correct
    (is (nil? (:overlap-maps (search-term-articles-summary search-term-first))))
    (is (= (search-term-articles-summary search-term-first)
           {:unique-articles 6, :reviewed-articles 0, :total-articles 6}))
    ;; delete the search-term-first source
    (delete-search-term-source search-term-first)
    ;; total sources is zero
    (taxi/wait-until #(= 0 (count (taxi/find-elements project-source-xpath))))
    (is (= 0 (count (taxi/find-elements project-source-xpath))))

;;; clean up
    (delete-current-project)
    (log-out)))
