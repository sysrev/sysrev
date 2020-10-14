(ns sysrev.test.browser.ctgov
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer [use-fixtures]]
            [clojure.tools.logging :as log]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :refer [xpath] :as x]
            [sysrev.test.core :as test :refer [default-fixture]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def ctgov-search-input "form#search-bar.ctgov-search input[type=text]")

(defn search-ctgov
  "Enter and submit a CT.gov search query."
  [query]
  (log/info "running CT.gov search:" (pr-str query))
  (b/wait-until-loading-completes :pre-wait true)
  (when-not (taxi/exists? ctgov-search-input)
    (b/click "a.tab-ctgov"))
  (b/wait-until-displayed ctgov-search-input)
  (Thread/sleep 20)
  (b/click ".ui.button.close-search" :if-not-exists :skip)
  (b/set-input-text ctgov-search-input query)
  (taxi/submit "form#search-bar.ctgov-search")
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(defn search-articles
  "Navigate to Articles page and enter a text search."
  [query]
  (log/info "running article search:" (pr-str query))
  (b/click ".project-menu a.item.articles" :displayed? true)
  (b/set-input-text "input#article-search" query)
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(deftest-browser ctgov-search-import
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (clinicaltrials.gov)"
   search-term "foo olive"
   article-title "Bioactivity of Olive Oils Enriched With Their Own Phenolic Compounds"
   article-search-result (xpath "//div[contains(@class,'article-title') and contains(text(),'"
                                article-title "')]")
   article-title-element (xpath "//h2[contains(text(),'" article-title "')]")]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (b/click "#enable-import")
      (b/select-datasource "ClinicalTrials.gov")
      (search-ctgov search-term)
      (log/info "importing clinical trials from search")
      (b/click x/import-button-xpath)
      (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                      :timeout 10000 :interval 30)
      ;; go back to articles and search for particular articles
      (search-articles article-title)
      (b/click article-search-result)
      (b/exists? article-title-element)))
