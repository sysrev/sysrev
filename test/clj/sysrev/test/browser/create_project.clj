(ns sysrev.test.browser.create-project
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.test.browser.core :as browser]
            [sysrev.test.browser.navigate :refer [log-in log-out]]
            [clojure.tools.logging :as log]))

;; manual tests
;; (sysrev.init/start-app {:dbname "sysrev_test"}) ; start the app, if
;; not already running
;; (browser/start-visual-webdriver)
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
    (log/info "running PubMed search:" (pr-str query))
    (taxi/wait-until #(and (taxi/exists? search-form)
                           (taxi/exists? search-input)))
    (browser/click
     {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}
     :if-not-exists :skip)
    (browser/set-input-text search-input query)
    (taxi/submit search-form)
    (Thread/sleep 100)
    (browser/wait-until-loading-completes 20000)))

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
  (is (= (:count (pubmed/get-search-query-response search-term 1))
         (search-count))))

(defn click-pager
  "Given a nav string, click the link in the pager corresponding to that position"
  [nav]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(text(),'" nav "')]")}]
    (browser/click query)
    (browser/wait-until-loading-completes)))

(defn disabled-pager-link?
  "Given a nav string, check to see if that pager link is disabled"
  [nav]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(text(),'" nav "')]")}]
    (browser/wait-until-exists query)
    (boolean (re-matches #".*disabled" (taxi/attribute query :class)))))

(deftest pubmed-search
  (log-in)
  (browser/go-route "/pubmed-search")
  (is (browser/panel-exists? [:pubmed-search]))
  (testing "Various search terms will yield the correct pmid count"
    (search-term-count-matches? "foo bar"))
  (testing "A search term with no documents"
    (search-for "foo bar baz qux quux")
    (is (browser/exists?
         {:xpath "//h3[contains(text(),'No documents match your search terms')]"})))
  (testing "Pager works properly"
    (search-for "dangerous statistics three")
    (is (disabled-pager-link? "First"))
    (is (disabled-pager-link? "Prev"))
    ;; Go to next page
    (click-pager "Next")
    (is (= 2 (get-current-page-number)))
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
    (is (= 1 (get-current-page-number)))
    (log-out)))

(defn delete-current-project
  []
  (log/info "deleting current project")
  (browser/go-project-route "/settings")
  (browser/click {:xpath "//button[contains(text(),'Delete this Project')]"})
  (browser/click {:xpath "//button[text()='Yes']"})
  (browser/wait-until-exists {:xpath "//h3[contains(text(),'Create a New Project')]"}))

(defn search-term-source-div-xpath
  "Given a search term, return a string of the xpath corresponding to the
  project-source div"
  [search-term]
  (str "//div[contains(@class,'import-label') and text()='\""
       search-term
       "\"']/ancestor::div[@class='project-source']"))

(defn search-term-delete-xpath
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (str (search-term-source-div-xpath search-term)
       "/descendant::div[contains(@class,'button') and contains(text(),'Delete')]"))

(defn search-term-articles-summary
  "Given a search term, return the map corresponding to the overlap of the
  articles of the form:
  {:unique-articles <int>
   :reviewed-articles <int>
   :total-articles <int>
   :overlap [{:overlap-count <int>
              :source string}]"
  [search-term]
  (let [[reviewed unique & overlap]
        (-> {:xpath (str (search-term-source-div-xpath search-term)
                         "/descendant::div[contains(@class,'source-description')]")}
            taxi/text
            (str/split #"\n"))
        [_ reviewed total] (re-matches #"(\d*) of (\d*)(?:\s|\S)*" reviewed)
        overlap-string->overlap-map
        (fn [overlap-string]
          (let [[_ overlap source]
                (re-matches #"(\d*) article(?:s)? shared with (.*)"
                            overlap-string)]
            (if-not (and (nil? overlap) (nil? source))
              {:overlap (read-string overlap) :source source})))
        summary-map
        {:unique-articles (->> (re-matches #"(\d*) unique article(?:s)?" unique)
                               second read-string)
         :reviewed-articles (read-string reviewed)
         :total-articles (read-string total)}]
    (if-not (empty? overlap)
      (assoc summary-map :overlap-maps
             (set (mapv overlap-string->overlap-map overlap)))
      summary-map)))

(def import-button-xpath
  {:xpath "//div[contains(@class,'button') and contains(text(),'Import')]"})

(defn add-articles-from-search-term
  "Create a new source using search-term"
  [search-term]
  ;; add articles from first search term
  (search-for search-term)
  (log/info "importing articles from search")
  (browser/click import-button-xpath)
  ;; check that they've loaded
  (is (browser/exists?
       {:xpath (str (search-term-source-div-xpath search-term)
                    "/descendant::div[contains(text(),'reviewed')]")})))

(defn delete-search-term-source
  [search-term]
  (let [delete {:xpath (search-term-delete-xpath search-term)}]
    (log/info "deleting article source")
    (browser/click delete :delay 100)))

(def project-title-xpath
  {:xpath "//span[contains(@class,'project-title')]"})
(def article-sources-list-xpath
  {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]"})
(def project-source-xpath
  {:xpath "//h4[contains(text(),'Article Sources')]//ancestor::div[@id='project-sources']/descendant::div[contains(@class,'project-sources-list')]/descendant::div[contains(@class,'project-source')]"})

(deftest create-project-and-import-sources
  (try
    (let [project-name "Sysrev Browser Test"
          search-term-first "foo bar"
          search-term-second "grault"
          search-term-third "foo bar Aung"
          search-term-fourth "foo bar Jones"]
      (log-in)
;;; create a project

      (browser/go-route "/select-project")
      (browser/set-input-text {:xpath "//input[@placeholder='Project Name']"}
                              project-name)

      (log/info "creating project")
      (browser/click {:xpath "//button[text()='Create']"} :delay 200)
      (browser/wait-until-displayed project-title-xpath)
      ;; was the project actually created?
      (is (str/includes? (taxi/text project-title-xpath) project-name))
      (browser/go-project-route "/add-articles")

;;; add sources
      ;; create a new source
      (add-articles-from-search-term search-term-first)
      ;; check that there is one article source listed
      (taxi/wait-until #(= 1 (count (taxi/find-elements article-sources-list-xpath)))
                       10000 75)
      (is (= 1 (count (taxi/find-elements article-sources-list-xpath))))
      (browser/click
       {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}
       :if-not-exists :skip)
      (when false
        ;; add articles from second search term
        (add-articles-from-search-term search-term-second)
        ;; check that there is one article source listed
        (taxi/wait-until #(= 2 (count (taxi/find-elements project-source-xpath)))
                         10000 50)
        (is (= 2 (count (taxi/find-elements project-source-xpath))))
        ;; check that there is no overlap
        (is (and (empty? (:overlap-maps (search-term-articles-summary search-term-first)))
                 (empty? (:overlap-maps (search-term-articles-summary search-term-second))))))
      ;; add articles from third search term
      (add-articles-from-search-term search-term-third)
      ;; search-term-third has no unique article or reviewed articles, only one article and one overlap with "foo bar"
      (is (= {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
              :overlap-maps (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])}
             (search-term-articles-summary search-term-third)))
      ;; add articles from fourth search term
      (add-articles-from-search-term search-term-fourth)
      ;; search-term-first has 4 unique articles, 0 reviewed articles, 6 total articles, and have two overalaps
      (is (= (search-term-articles-summary search-term-first)
             {:unique-articles 4, :reviewed-articles 0, :total-articles 6,
              :overlap-maps (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}
                                  {:overlap 1, :source "PubMed Search \"foo bar Jones\""}])}))

;;; delete sources
      ;; delete the search-term-first source
      (delete-search-term-source search-term-fourth)
      ;; total sources is three
      (taxi/wait-until #(= 2 (count (taxi/find-elements project-source-xpath)))
                       10000 50)
      (is (= 2 (count (taxi/find-elements project-source-xpath))))
      ;; article summaries are correct
      (is (= (search-term-articles-summary search-term-first)
             {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
              :overlap-maps (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}])}))
      (is (= (search-term-articles-summary search-term-third)
             {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
              :overlap-maps (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])}))
      (when false
        ;; delete the search-term-second source
        (delete-search-term-source search-term-second)
        ;; total sources is two
        (taxi/wait-until #(= 2 (count (taxi/find-elements project-source-xpath)))
                         10000 50)
        (is (= 2 (count (taxi/find-elements project-source-xpath))))
        ;; article summaries are correct
        (is (= (search-term-articles-summary search-term-first)
               {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
                :overlap-maps (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}])}))
        (is (= (search-term-articles-summary search-term-third)
               {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
                :overlap-maps (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])})))
      ;; delete the search-term-third source
      (delete-search-term-source search-term-third)
      ;; total sources is one
      (taxi/wait-until #(= 1 (count (taxi/find-elements project-source-xpath)))
                       10000 50)
      (is (= 1 (count (taxi/find-elements project-source-xpath))))
      ;; article summaries are correct
      (is (empty? (:overlap-maps (search-term-articles-summary search-term-first))))
      (is (= (search-term-articles-summary search-term-first)
             {:unique-articles 6, :reviewed-articles 0, :total-articles 6}))
      ;; delete the search-term-first source
      (delete-search-term-source search-term-first)
      ;; total sources is zero
      (taxi/wait-until #(= 0 (count (taxi/find-elements project-source-xpath)))
                       10000 50)
      (is (= 0 (count (taxi/find-elements project-source-xpath)))))
    (finally
;;; clean up
      (delete-current-project)
      (log-out))))
