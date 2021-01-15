(ns sysrev.test.browser.project-articles-data
  (:require [clojure.test :refer [is use-fixtures]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]
            [sysrev.project.article-list :refer
             [article-ids-from-title-search-local
              article-ids-from-content-search-local]]
            [clj-webdriver.taxi :as taxi]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def article-list-item ".ui.segment.articles-data-table.title")

(defn visible-articles-count []
  (count (->> (taxi/elements article-list-item)
              (filter taxi/displayed?))))

(deftest-browser basic-article-search
  true test-user
  [input "#article-search"
   project-id (atom nil)
   title-count (fn [text] (count (article-ids-from-title-search-local @project-id text)))
   content-count (fn [text] (count (article-ids-from-content-search-local @project-id text)))
   is-visible-count (fn [value] (b/is-soon #(= value (visible-articles-count))))
   is-db-count (fn [count-fn text value]
                 (when (test/db-connected?)
                   (is (= value (count-fn text)))))]
  (do (nav/log-in (:email test-user))
      (nav/new-project "basic-article-search test")
      (reset! project-id (b/current-project-id))
      (assert (integer? @project-id))
      (pm/import-pubmed-search-via-db "foo bar")
      (nav/go-project-route "/data")
      (is-visible-count 6)
      (b/set-input-text-per-char input "CO2")
      (is-visible-count 3)
      (is-db-count title-count "CO2" 2)
      (b/set-input-text-per-char input " earth" :clear? false)
      (is-visible-count 1)
      (b/set-input-text input "")
      (is-visible-count 6))
  :cleanup (nav/delete-current-project))
