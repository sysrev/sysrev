(ns sysrev.test.browser.pubmed
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.db.core :as db]
            [sysrev.source.import :as import]
            [sysrev.pubmed :as pubmed]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.shared.util :as sutil :refer [parse-integer]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn search-pubmed
  "Enter and submit a PubMed search query"
  [query]
  (log/info "running PubMed search:" (pr-str query))
  (b/wait-until #(and (taxi/exists? x/pubmed-search-form)
                      (taxi/exists? x/pubmed-search-input)))
  (Thread/sleep 10)
  (-> {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}
      (b/click :if-not-exists :skip))
  (b/set-input-text x/pubmed-search-input query)
  (taxi/submit x/pubmed-search-form)
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(defn search-count
  "Return an integer item count of search results"
  []
  (let [pager-message {:xpath "//h5[contains(@class,'list-pager-message')]"}
        pubmed-article {:xpath "//div[contains(@class,'pubmed-article')]"}]
    (b/wait-until-displayed pager-message)
    (b/wait-until-displayed pubmed-article)
    (->> (taxi/find-elements pager-message)
         first
         taxi/text
         (re-matches #".*of (\d*).*")
         last
         parse-integer)))

(defn max-pages
  "Return max number of pages"
  []
  (let [page-number {:xpath "//span[contains(@class,'page-number')]"}]
    (b/wait-until-displayed page-number)
    (->> (taxi/text (taxi/find-element page-number))
         (re-matches #"(.|\s)*of (\d*).*")
         last
         parse-integer)))

(defn get-current-page-number
  []
  (->> {:xpath "//input[contains(@class,'page-number') and @type='text']"}
       taxi/find-element taxi/value parse-integer))

(defn search-term-count-matches?
  "Does the search-term result in the browser match the remote call?"
  [search-term]
  (search-pubmed search-term)
  (is (= (:count (pubmed/get-search-query-response search-term 1))
         (search-count))))

(defn click-pager
  "Given a nav string, click the link in the pager corresponding to that position"
  [nav]
  (b/click (xpath "//div[contains(@class,'button') and contains(text(),'" nav "')]")))

(defn click-button-class [class]
  (b/click (format ".ui.button.%s" class)))

(defn disabled-pager-link?
  "Given a nav string, check to see if that pager link is disabled"
  [nav]
  (let [query (xpath "//div[contains(@class,'button') and contains(text(),'" nav "')]")]
    (b/wait-until-exists query)
    (boolean (re-matches #".*disabled.*" (taxi/attribute query :class)))))

(defn search-term-articles-summary
  "Given a search term, return the map corresponding to the overlap of
  the articles of the form:

  {:unique-articles <int>, :reviewed-articles <int>, :total-articles <int>
   :overlap [{:overlap-count <int>, :source string} ...]"
  [search-term]
  (let [span-xpath #(x/search-source search-term
                                     (format "/descendant::span[contains(@class,'%s')]" %))
        read-count #(-> (span-xpath %) taxi/text (str/split #",") str/join parse-integer)
        reviewed (read-count "reviewed-count")
        unique (read-count "unique-count")
        total (read-count "total-count")
        #_ overlap #_ {}
        #_ make-overlap-map #_ (fn [overlap-string]
                                 (let [[_ overlap source]
                                       (re-matches #"(\d*) article(?:s)? shared with (.*)"
                                                   overlap-string)]
                                   (if-not (and (nil? overlap) (nil? source))
                                     {:overlap (parse-integer overlap) :source source})))]
    (-> {:unique-articles unique
         :reviewed-articles reviewed
         :total-articles total}
        #_ (cond-> (seq overlap)
             (assoc :overlap-maps (set (mapv make-overlap-map overlap)))))))

(def import-button-xpath
  {:xpath "//button[contains(@class,'button') and contains(text(),'Import')]"})

(defn get-source-count []
  (count (taxi/find-elements x/project-source)))

(defn check-source-count [n]
  (b/is-soon (= n (get-source-count)) 8000 30))

(defn add-articles-from-search-term [search-term]
  (nav/go-project-route "/add-articles" :wait-ms 50)
  (let [initial-count (get-source-count)]
    (search-pubmed search-term)
    (log/info "importing articles from search")
    (b/click import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 2
                                    :timeout 10000 :interval 30)
    (check-source-count (inc initial-count))
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 2
                                    :timeout 10000 :interval 30)
    (nav/wait-until-overview-ready)))

;; This doesn't work against staging.sysrev.com - cache not cleared?
(defn import-pmids-via-db
  "Use direct database connection to import articles by PMID, getting
  project from current browser URL."
  [pmids]
  (assert pmids)
  (let [project-id (b/current-project-id)]
    (assert project-id)
    (log/infof "importing (%d) pmid articles to project (#%d)"
               (count pmids) project-id)
    (import/import-pmid-vector project-id {:pmids pmids} {:use-future? false})
    (Thread/sleep 20)
    (b/init-route (str "/p/" project-id "/add-articles") :silent true)))

(def test-search-pmids
  {"foo bar" [25706626 25215519 23790141 22716928 19505094 9656183]
   "foo bar enthalpic mesoporous" [25215519]})

(defn import-pubmed-search-via-db
  "Use direct database connection to import articles corresponding to a
  pre-defined PubMed search, or if db is not connected fall back to
  importing by PubMed search through web interface."
  [search-term]
  (let [pmids (test-search-pmids search-term)]
    (if (and pmids @db/active-db (not (test/remote-test?)))
      (import-pmids-via-db pmids)
      (add-articles-from-search-term search-term))))

(defn delete-search-term-source [search-term]
  (b/wait-until-loading-completes :pre-wait 25 :inactive-ms 75 :loop 3)
  (log/info "deleting article source")
  (b/click (x/search-term-delete search-term))
  (b/wait-until-loading-completes :pre-wait 50 :inactive-ms 100 :loop 4))

(deftest-browser pubmed-search
  true []
  (do (nav/log-in)
      (nav/go-route "/pubmed-search")
      (is (nav/panel-exists? [:pubmed-search]))
      (testing "Various search terms will yield the correct pmid count"
        (search-term-count-matches? "foo bar"))
      (testing "A search term with no documents"
        (search-pubmed "foo bar baz qux quux")
        (is (b/exists?
             {:xpath "//h3[contains(text(),'No documents match your search terms')]"})))
      (testing "Pager works properly"
        (search-pubmed "dangerous statistics three")
        ;; (is (disabled-pager-link? "First"))
        (is (disabled-pager-link? "Previous"))
        ;; Go to next page
        (click-pager "Next")
        (is (= 2 (get-current-page-number)))
        ;; Go to last page
        (click-button-class "nav-last")
        (is (disabled-pager-link? "Next"))
        ;; (is (disabled-pager-link? "Last"))
        (is (= (max-pages)
               (get-current-page-number)))
        ;; Go back one page
        (click-pager "Previous")
        (is (= (- (max-pages) 1)
               (get-current-page-number)))
        ;; Go to first page
        (click-button-class "nav-first")
        (is (= 1 (get-current-page-number)))
        (nav/log-out))))
