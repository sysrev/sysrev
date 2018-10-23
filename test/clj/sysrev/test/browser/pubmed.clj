(ns sysrev.test.browser.pubmed
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.import.pubmed :as import]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.shared.util :as util]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn search-pubmed
  "Enter and submit a PubMed search query"
  [query]
  (log/info "running PubMed search:" (pr-str query))
  (taxi/wait-until #(and (taxi/exists? x/pubmed-search-form)
                         (taxi/exists? x/pubmed-search-input)))
  (-> {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}
      (b/click :if-not-exists :skip))
  (b/set-input-text x/pubmed-search-input query)
  (taxi/submit x/pubmed-search-form)
  (b/wait-until-loading-completes
   :pre-wait 100 :timeout 20000))

(defn search-count
  "Return an integer item count of search results"
  []
  (let [pager-message {:xpath "//h5[contains(@class,'list-pager-message')]"}
        pubmed-article {:xpath "//div[contains(@class,'pubmed-article')]"}]
    (b/wait-until-exists pager-message)
    (b/wait-until-exists pubmed-article)
    (->> (taxi/find-elements pager-message)
         first
         taxi/text
         (re-matches #".*of (\d*).*")
         last
         util/parse-integer)))

(defn max-pages
  "Return max number of pages"
  []
  (let [page-number {:xpath "//span[contains(@class,'page-number')]"}]
    (b/wait-until-exists page-number)
    (->> (taxi/text (taxi/find-element page-number))
         (re-matches #"(.|\s)*of (\d*).*")
         last
         util/parse-integer)))

(defn get-current-page-number
  []
  (->> {:xpath "//input[contains(@class,'page-number') and @type='text']"}
       taxi/find-element taxi/value util/parse-integer))

(defn search-term-count-matches?
  "Does the search-term result in the browser match the remote call?"
  [search-term]
  (search-pubmed search-term)
  (is (= (:count (import/get-search-query-response search-term 1))
         (search-count))))

(defn click-pager
  "Given a nav string, click the link in the pager corresponding to that position"
  [nav]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(text(),'" nav "')]")}]
    (b/click query)))

(defn click-button-class
  [class]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(@class,'" class "')]")}]
    (b/click query)))

(defn disabled-pager-link?
  "Given a nav string, check to see if that pager link is disabled"
  [nav]
  (let [query {:xpath (str "//div[contains(@class,'button') and contains(text(),'" nav "')]")}]
    (b/wait-until-exists query)
    (boolean (re-matches #".*disabled.*" (taxi/attribute query :class)))))

(defn search-term-articles-summary
  "Given a search term, return the map corresponding to the overlap of the
  articles of the form:
  {:unique-articles <int>
   :reviewed-articles <int>
   :total-articles <int>
   :overlap [{:overlap-count <int>
              :source string}]"
  [search-term]
  (let [reviewed
        (-> (x/search-source
             search-term
             "/descendant::span[contains(@class,'reviewed-count')]")
            taxi/text
            (str/split #",")
            (str/join)
            util/parse-integer)
        unique
        (-> (x/search-source
             search-term
             "/descendant::span[contains(@class,'unique-count')]")
            taxi/text
            (str/split #",")
            (str/join)
            util/parse-integer)
        total
        (-> (x/search-source
             search-term
             "/descendant::span[contains(@class,'total-count')]")
            taxi/text
            (str/split #",")
            (str/join)
            util/parse-integer)
        overlap {}
        #_ [reviewed unique & overlap]
        #_ (-> (x/search-source
                search-term
                "/descendant::div[contains(@class,'source-description')]")
               taxi/text
               (str/split #"\n"))
        #_ [_ reviewed total] #_ (re-matches #"(\d*) of (\d*)(?:\s|\S)*" reviewed)
        overlap-string->overlap-map
        (fn [overlap-string]
          (let [[_ overlap source]
                (re-matches #"(\d*) article(?:s)? shared with (.*)"
                            overlap-string)]
            (if-not (and (nil? overlap) (nil? source))
              {:overlap (util/parse-integer overlap) :source source})))
        summary-map
        {:unique-articles unique
         :reviewed-articles reviewed
         :total-articles total}
        #_ {:unique-articles (->> (re-matches #"(\d*) unique article(?:s)?" unique)
                                  second util/parse-integer)
            :reviewed-articles (util/parse-integer reviewed)
            :total-articles (util/parse-integer total)}]
    (if-not (empty? overlap)
      (assoc summary-map :overlap-maps
             (set (mapv overlap-string->overlap-map overlap)))
      summary-map)))

(def import-button-xpath
  {:xpath "//button[contains(@class,'button') and contains(text(),'Import')]"})

(defn get-source-count []
  (count (taxi/find-elements x/project-source)))

(defn check-source-count [n]
  (taxi/wait-until #(= n (get-source-count)) 15000 25)
  (is (= n (get-source-count))))

(defn add-articles-from-search-term [search-term]
  (nav/go-project-route "/add-articles")
  (let [initial-count (get-source-count)]
    (search-pubmed search-term)
    (log/info "importing articles from search")
    (b/click import-button-xpath)
    (Thread/sleep 250)
    (check-source-count (inc initial-count))
    (b/wait-until-loading-completes :pre-wait 500)
    #_ (b/wait-until-loading-completes :pre-wait 500)
    #_ (is (b/exists? (x/search-source search-term)))
    #_ (is (b/exists?
            (x/search-source
             search-term "/descendant::span[contains(@class,'reviewed-count')]")))
    (nav/go-project-route "")
    (nav/wait-until-overview-ready)))

(defn delete-search-term-source [search-term]
  (b/wait-until-loading-completes :pre-wait 200)
  (log/info "deleting article source")
  (b/click (x/search-term-delete search-term))
  (b/wait-until-loading-completes :pre-wait 500)
  (b/wait-until-loading-completes :pre-wait 500))

(deftest-browser pubmed-search
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
