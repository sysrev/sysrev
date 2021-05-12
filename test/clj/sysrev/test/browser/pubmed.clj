(ns sysrev.test.browser.pubmed
  (:require [clojure.test :refer [is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.db.core :as db]
            [sysrev.source.import :as import]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.util :as util :refer [parse-integer]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def pubmed-search-input "form#search-bar input[type=text]")

(defn search-pubmed
  "Enter and submit a PubMed search query"
  [query]
  (log/info "running PubMed search:" (pr-str query))
  (when-not (taxi/exists? pubmed-search-input)
    (b/select-datasource "PubMed"))
  (b/wait-until-exists pubmed-search-input)
  (Thread/sleep 20)
  (b/click ".ui.button.close-search" :if-not-exists :skip)
  (b/set-input-text pubmed-search-input query)
  (taxi/submit "form#search-bar")
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(defn search-count
  "Return an integer item count of search results"
  []
  (b/wait-until-displayed "h5.list-pager-message")
  (b/wait-until-displayed "div.pubmed-article")
  (parse-integer (->> (taxi/elements "h5.list-pager-message")
                      first
                      taxi/text
                      (re-matches #".*of (\d*).*")
                      last)))

(defn max-pages
  "Return max number of pages"
  []
  (b/wait-until-displayed "span.page-number")
  (parse-integer (->> (taxi/text (taxi/element "span.page-number"))
                      (re-matches #"(.|\s)*of (\d*).*")
                      last)))

(defn get-current-page-number []
  (parse-integer (-> "input.page-number" taxi/element taxi/value)))

(defn search-term-count-matches?
  "Does the search-term result in the browser match the remote call?"
  [search-term]
  (search-pubmed search-term)
  (is (= (:count (pubmed/get-search-query-response search-term 1))
         (search-count))))

(defn click-pager
  "Given a nav string, click the link in the pager corresponding to that position"
  [nav]
  (b/click (xpath "//button[contains(@class,'button') and contains(text(),'" nav "')]")))

(defn click-button-class [class]
  (b/click (format ".ui.button.%s" class)))

(defn disabled-pager-link?
  "Given a nav string, check to see if that pager link is disabled"
  [nav]
  (boolean (-> (xpath "//button[contains(@class,'button') and contains(text(),'" nav "')]")
               (taxi/element)
               (taxi/attribute :class)
               (->> (re-matches #".*disabled.*")))))

(defn search-term-articles-summary
  "Given a search term, return the map corresponding to the overlap of
  the articles of the form:

  {:unique-articles <int>, :reviewed-articles <int>, :total-articles <int>
   :overlap [{:overlap-count <int>, :source string} ...]"
  [search-term]
  (let [span-xpath #(xpath (x/search-source search-term)
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

(defn get-source-count []
  (count (taxi/find-elements x/project-source)))

(defn check-source-count [n]
  (b/is-soon (= n (get-source-count)) 10000 40))

(defn add-articles-from-search-term [search-term]
  (nav/go-project-route "/add-articles" :wait-ms 75)
  (b/wait-until-displayed "#enable-import")
  (when (taxi/exists? "#enable-import-dismiss")
    (b/click "#enable-import-dismiss"))
  (when (taxi/exists? (b/not-disabled "#enable-import"))
    (b/click "#enable-import"))
  (b/wait-until-displayed ".datasource-item")
  (let [initial-count (get-source-count)]
    (search-pubmed search-term)
    (log/info "importing articles from search")
    (b/click x/import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (check-source-count (inc initial-count))
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
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
  {"foo bar" [33222245 32891636 25706626 25215519 23790141 22716928 19505094 9656183]
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

(defn edit-search-term-source [search-term]
  (b/wait-until-loading-completes :pre-wait 75 :inactive-ms 100 :loop 3)
  (log/info "editing article source")
  (b/click (x/search-term-edit search-term))
  (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 4))

(defn delete-search-term-source [search-term]
  (b/wait-until-loading-completes :pre-wait 75 :inactive-ms 100 :loop 3)
  (log/info "deleting article source")
  (b/click (x/search-term-delete search-term))
  (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 4))

(deftest-browser pubmed-search
  true test-user
  [page-num-is #(is (= (get-current-page-number) %))]
  (do (nav/log-in (:email test-user))
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
        (is (disabled-pager-link? "Previous"))
        (click-pager "Next")            ; Go to next page
        (page-num-is 2)
        (click-button-class "nav-last") ; Go to last page
        (is (disabled-pager-link? "Next"))
        (page-num-is (max-pages))
        (click-pager "Previous")        ; Go back one page
        (page-num-is (dec (max-pages)))
        (click-button-class "nav-first") ; Go to first page
        (page-num-is 1)
        (nav/log-out))))
