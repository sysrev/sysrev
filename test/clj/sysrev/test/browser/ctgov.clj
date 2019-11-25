(ns sysrev.test.browser.ctgov
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :refer [xpath] :as x]
            [sysrev.test.core :as test :refer [default-fixture]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def ctgov-input-search (xpath "//input[contains(@placeholder,'Search ClinicalTrials.gov')]"))

(defn search-ctgov
  "Enter and submit a PubMed search query"
  [query]
  (log/info "running CT.gov search:" (pr-str query))
  (when-not (taxi/exists? ctgov-input-search)
    (b/click (xpath "//a[contains(@class,'tab-ctgov')]")))
  (b/wait-until #(taxi/exists? ctgov-input-search))
  (Thread/sleep 10)
  (-> {:xpath "//div[contains(@class,'button') and contains(text(),'Close')]"}
      (b/click :if-not-exists :skip))
  (b/set-input-text ctgov-input-search query)
  (taxi/submit x/pubmed-search-form)
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(def search-articles-input (xpath "//input[contains(@placeholder,'Search articles')]"))

(def articles-icon (xpath "//a[contains(@class,'item') and contains(@class,'articles')]"))

(defn search-articles
  "Enter and submit a PubMed search query"
  [query]
  (log/info "running article search:" (pr-str query))
  (b/wait-until-displayed articles-icon)
  (b/click articles-icon)
  (b/wait-until #(taxi/exists? search-articles-input))
  (Thread/sleep 10)
  (b/set-input-text search-articles-input query)
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(deftest-browser happy-path
  (and (test/db-connected?) (not (test/remote-test?)))
  [project-name "SysRev Browser Test (clinicaltrials.gov)"
   search-term "foo olive"
   article-title "Bioactivity of Olive Oils Enriched With Their Own Phenolic Compounds"
   article-search-result (xpath "//div[contains(@class,'article-title') and contains(text(),'" article-title "')]")
   article-title-element (xpath "//h2[contains(text(),'" article-title "')]")      ]
  (do (nav/log-in)
      (nav/new-project project-name)
      (b/click (xpath "//a[contains(@class,'tab-ctgov')]"))
      (search-ctgov search-term)
      (log/info "importing clinical trials from search")
      (b/click x/import-button-xpath)
      (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                      :timeout 10000 :interval 30)
      ;; go back to articles and search for particular articles
      (search-articles article-title)
      (b/wait-until-displayed article-search-result)
      (b/click article-search-result)
      (b/wait-until-displayed article-title-element)
      (is (b/exists? article-title-element)))
  :cleanup (b/cleanup-test-user! :email (:email b/test-login)))
