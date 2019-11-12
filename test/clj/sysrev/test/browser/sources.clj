(ns sysrev.test.browser.sources
  (:require [clojure.test :refer [is use-fixtures]]
            [clojure.java.io :as io]
            [clj-webdriver.taxi :as taxi]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.query-types :as qt]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.xpath :refer [xpath]]
            [sysrev.source.import :as import]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser import-pubmed-sources
  true
  [project-name "Sysrev Browser Test (import-pubmed-sources)"
   query1 "foo bar"
   query2 "grault"
   query3 "foo bar Aung"
   query4 "foo bar Jones"
   project-id (atom nil)]
  (do (nav/log-in)
;;; create a project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (assert (integer? @project-id))
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
      ;; query3 has no unique article or reviewed articles, only one
      ;; article and one overlap with "foo bar"
      (is (= {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
              #_ :overlap-maps #_ (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])}
             (pm/search-term-articles-summary query3)))
      ;; add articles from fourth search term
      (pm/add-articles-from-search-term query4)
      (nav/go-project-route "/add-articles")
      ;; query1 has 4 unique articles, 0 reviewed articles, 6 total
      ;; articles, and have two overalaps
      (is (= (pm/search-term-articles-summary query1)
             {:unique-articles 4, :reviewed-articles 0, :total-articles 6,
              #_ :overlap-maps
              #_ (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}
                       {:overlap 1, :source "PubMed Search \"foo bar Jones\""}])}))

;;; delete sources
      (pm/delete-search-term-source query4)
      (pm/check-source-count 2)
      ;; article summaries are correct
      (is (= (pm/search-term-articles-summary query1)
             {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
              #_ :overlap-maps
              #_ (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}])}))
      (is (= (pm/search-term-articles-summary query3)
             {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
              #_ :overlap-maps
              #_ (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])}))
      (when false
        (pm/delete-search-term-source query2)
        (pm/check-source-count 2)
        ;; article summaries are correct
        (is (= (pm/search-term-articles-summary query1)
               {:unique-articles 5, :reviewed-articles 0, :total-articles 6,
                #_ :overlap-maps
                #_ (set [{:overlap 1, :source "PubMed Search \"foo bar Aung\""}])}))
        (is (= (pm/search-term-articles-summary query3)
               {:unique-articles 0, :reviewed-articles 0, :total-articles 1,
                #_ :overlap-maps
                #_ (set [{:overlap 1, :source "PubMed Search \"foo bar\""}])})))
      (pm/delete-search-term-source query3)
      (pm/check-source-count 1)
      ;; article summaries are correct
      (is (empty? (:overlap-maps (pm/search-term-articles-summary query1))))
      (is (= (pm/search-term-articles-summary query1)
             {:unique-articles 6, :reviewed-articles 0, :total-articles 6}))
      (pm/delete-search-term-source query1)
      (pm/check-source-count 0))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)))

(deftest-browser pdf-interface
  (and (test/db-connected?) (not (test/remote-test?)))
  [filename "test-pdf-import.zip"
   file (-> (str "test-files/" filename) io/resource io/file)]
  (do (nav/log-in)
      (nav/new-project "pdf-interface test")
      (import/import-pdf-zip (b/current-project-id) {:file file :filename filename}
                             {:use-future? false})
      (b/init-route (-> (taxi/current-url) b/url->path))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.column.article-title" :displayed? true :delay 200)
      (b/is-soon (taxi/exists? "div.pdf-container div.page div.canvasWrapper"))
      (b/click ".ui.menu > .item.articles" :delay 100)
      (b/is-soon (taxi/exists? "a.column.article-title")))
  :cleanup (b/cleanup-test-user! :email (:email b/test-login)))

(deftest-browser import-ris-file
  true
  [project-name "SysRev Browser Test (import-ris-file)"
   title "Long Short-Term Memory"]
  (do (nav/log-in)
      (nav/new-project project-name)
      (b/click (xpath "//a[contains(text(),'RIS File')]"))
      (b/dropzone-upload "test-files/IEEE_Xplore_Citation_Download_LSTM_top_10.ris")
      (b/wait-until-exists (xpath "//div[contains(@class,'source-type') and contains(text(),'RIS file')]"))
      (is (b/exists? (xpath "//span[@class='unique-count' and contains(text(),'10')]")))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click (xpath "//div[contains(@class,'article-title') and text()='" title "']"))
      (let [project-id (b/current-project-id)
            ;; below should be replaced by article search
            ;; text once that is completed
            article-id (-> (qt/find-article {:a.project-id project-id :ad.title title } [:a.article-id])
                           first :article-id)
            {:keys [authors abstract primary-title secondary-title date]} (ds-api/get-article-content article-id)
            abstract-excerpt "Learning to store information over extended time intervals"]
        (is (b/exists? (xpath "//span[text()='" primary-title "']")))
        (is (b/exists? (xpath "//span[text()='" secondary-title "']")))
        (is (b/exists? (xpath "//h5[text()='" date "']")))
        (is (b/exists? (xpath "//h5[text()='" (clojure.string/join ", " authors) "']")))
        (is (b/exists? (xpath "//span[contains(text(),'" abstract-excerpt  "')]")))
        (is (= abstract
               (first (b/get-elements-text (xpath "//span[contains(text(),'" abstract-excerpt  "')]"))))))
      :cleanup (b/cleanup-test-user! :email (:email b/test-login))))
