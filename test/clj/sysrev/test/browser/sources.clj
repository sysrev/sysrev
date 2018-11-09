(ns sysrev.test.browser.sources
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.pubmed :as pm]
            [clojure.tools.logging :as log]
            [sysrev.shared.util :as util]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser import-pubmed-sources
  (let [project-name "Sysrev Browser Test (import-pubmed-sources)"
        query1 "foo bar"
        query2 "grault"
        query3 "foo bar Aung"
        query4 "foo bar Jones"]
    (nav/log-in)
;;; create a project
    (nav/new-project project-name)
    (nav/go-project-route "/add-articles")
;;; add sources
    ;; create a new source
    (pm/add-articles-from-search-term query1)
    (nav/go-project-route "/add-articles")
    (when false
      ;; add articles from second search term
      (pm/add-articles-from-search-term query2)
      (nav/go-project-route "/add-articles")
      ;; check that there is no overlap
      (is (and (empty? (:overlap-maps (pm/search-term-articles-summary query1)))
               (empty? (:overlap-maps (pm/search-term-articles-summary query2))))))
    ;; add articles from third search term
    (pm/add-articles-from-search-term query3)
    (nav/go-project-route "/add-articles")
    ;; query3 has no unique article or reviewed articles, only one article and one overlap with "foo bar"
    (is (= {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
            #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])}
           (pm/search-term-articles-summary query3)))
    ;; add articles from fourth search term
    (pm/add-articles-from-search-term query4)
    (nav/go-project-route "/add-articles")
    ;; query1 has 4 unique articles, 0 reviewed articles, 6 total articles, and have two overalaps
    (is (= (pm/search-term-articles-summary query1)
           {:unique-articles 4, :reviewed-articles 0, :total-articles 6,
            #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}
                                      {:overlap 1, :source "PubMed Search \"foo bar Jones\""}])}))

;;; delete sources
    (pm/delete-search-term-source query4)
    (pm/check-source-count 2)
    ;; article summaries are correct
    (is (= (pm/search-term-articles-summary query1)
           {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
            #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}])}))
    (is (= (pm/search-term-articles-summary query3)
           {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
            #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])}))
    (when false
      (pm/delete-search-term-source query2)
      (pm/check-source-count 2)
      ;; article summaries are correct
      (is (= (pm/search-term-articles-summary query1)
             {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
              #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}])}))
      (is (= (pm/search-term-articles-summary query3)
             {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
              #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])})))
    (pm/delete-search-term-source query3)
    (pm/check-source-count 1)
    ;; article summaries are correct
    (is (empty? (:overlap-maps (pm/search-term-articles-summary query1))))
    (is (= (pm/search-term-articles-summary query1)
           {:unique-articles 6, :reviewed-articles 0, :total-articles 6}))
    (pm/delete-search-term-source query1)
    (pm/check-source-count 0))

  :cleanup
  (do (nav/delete-current-project)
      (nav/log-out)))
