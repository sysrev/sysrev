(ns sysrev.test.browser.sources
  (:require [clojure.test :refer [is use-fixtures]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clj-webdriver.taxi :as taxi]
            [me.raynes.fs :as fs]
            [sysrev.db.queries :as q]
            [sysrev.datasource.api :as ds-api]
            [sysrev.source.import :as import]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser is*]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.xpath :refer [xpath]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn unique-count-span [n]
  (format "span.unique-count[data-count='%d']" n))

(deftest-browser import-pubmed-sources
  true test-user
  [project-name "Sysrev Browser Test (import-pubmed-sources)"
   query1 "foo bar"
   query2 "grault"
   query3 "foo bar Aung"
   query4 "foo bar Jones"
   project-id (atom nil)
   foo-bar-count (count (pm/test-search-pmids "foo bar"))]
  (do (nav/log-in (:email test-user))
;;; create a project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (assert (integer? @project-id))
;;; add sources
      (nav/go-project-route "/add-articles")
      (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                      :timeout 10000 :interval 30)
      (log/info "adding articles from query1")
      (pm/add-articles-from-search-term query1)
      (nav/go-project-route "/add-articles")
      (when false
        (pm/add-articles-from-search-term query2)
        (nav/go-project-route "/add-articles")
        ;; check that there is no overlap
        (is* (and (empty? (:overlap-maps (pm/search-term-articles-summary query1)))
                  (empty? (:overlap-maps (pm/search-term-articles-summary query2))))))
      (log/info "adding articles from query3")
      (pm/add-articles-from-search-term query3)
      (nav/go-project-route "/add-articles")
      (is* (= {:unique-articles 0 :reviewed-articles 0 :total-articles 1
               #_ :overlap-maps
               #_ (set [{:overlap 1 :source "PubMed Search \"foo bar\""}])}
              (pm/search-term-articles-summary query3)))
      (log/info "adding articles from query4")
      (pm/add-articles-from-search-term query4)
      (nav/go-project-route "/add-articles")
      (is* (= {:unique-articles (- foo-bar-count 2) :reviewed-articles 0
               :total-articles foo-bar-count
               #_ :overlap-maps
               #_ (set [{:overlap 1 :source "PubMed Search \"foo bar Aung\""}
                        {:overlap 1 :source "PubMed Search \"foo bar Jones\""}])}
              (pm/search-term-articles-summary query1)))

;;; delete sources
      (pm/delete-search-term-source query4)
      (pm/check-source-count 2)
      ;; article summaries are correct
      (is* (= {:unique-articles (- foo-bar-count 1) :reviewed-articles 0
               :total-articles foo-bar-count
               #_ :overlap-maps
               #_ (set [{:overlap 1 :source "PubMed Search \"foo bar Aung\""}])}
              (pm/search-term-articles-summary query1)))
      (is* (= {:unique-articles 0 :reviewed-articles 0 :total-articles 1
               #_ :overlap-maps
               #_ (set [{:overlap 1 :source "PubMed Search \"foo bar\""}])}
              (pm/search-term-articles-summary query3)))
      (when false
        (pm/delete-search-term-source query2)
        (pm/check-source-count 2)
        ;; article summaries are correct
        (is* (= {:unique-articles (- foo-bar-count 2) :reviewed-articles 0
                 :total-articles foo-bar-count
                 #_ :overlap-maps
                 #_ (set [{:overlap 1 :source "PubMed Search \"foo bar Aung\""}])}
                (pm/search-term-articles-summary query1)))
        (is* (= {:unique-articles 0 :reviewed-articles 0 :total-articles 1
                 #_ :overlap-maps
                 #_ (set [{:overlap 1 :source "PubMed Search \"foo bar\""}])}
                (pm/search-term-articles-summary query3))))
      (pm/delete-search-term-source query3)
      (pm/check-source-count 1)
      ;; article summaries are correct
      (is* (empty? (:overlap-maps (pm/search-term-articles-summary query1))))
      (is* (= {:unique-articles foo-bar-count :reviewed-articles 0
               :total-articles foo-bar-count}
              (pm/search-term-articles-summary query1)))
      (pm/delete-search-term-source query1)
      (pm/check-source-count 0))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)))

(deftest-browser pdf-interface
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [filename "test-pdf-import.zip"
   file (-> (str "test-files/" filename) io/resource io/file)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "pdf-interface test")
      (import/import-pdf-zip (b/current-project-id) {:file file :filename filename}
                             {:use-future? false})
      (b/init-route (-> (taxi/current-url) b/url->path))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.column.article-title" :displayed? true :delay 200)
      (b/is-soon (taxi/exists? "div.pdf-container div.page div.canvasWrapper"))
      (b/click ".ui.menu > .item.articles" :delay 100)
      (b/is-soon (taxi/exists? "a.column.article-title"))))

(deftest-browser import-ris-file
  ;; once article search by title is implemented, set to true
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (import-ris-file)"
   title "Long Short-Term Memory"]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (b/select-datasource "RIS / RefMan")
      (b/dropzone-upload "test-files/IEEE_Xplore_Citation_Download_LSTM_top_10.ris")
      (b/wait-until-exists (xpath "//div[contains(@class,'source-type') and contains(text(),'RIS file')]"))
      (b/exists? (unique-count-span 10))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click (xpath "//div[contains(@class,'article-title') and text()='" title "']"))
      (let [project-id (b/current-project-id)

            ;; below should be replaced by article search
            ;; text once that is completed
            article-id (q/find-article-1 {:a.project-id project-id :ad.title title}
                                         :a.article-id)
            {:keys [authors abstract primary-title secondary-title
                    date]} (ds-api/get-article-content article-id)]
        (is (b/exists? (xpath "//span[text()='" primary-title "']")))
        (is (b/exists? (xpath "//span[text()='" secondary-title "']")))
        (is (b/exists? (xpath "//h5[text()='" date "']")))
        (is (b/exists? (xpath "//h5[text()='" (str/join ", " authors) "']")))
        (let [excerpt "Learning to store information over extended time intervals"]
          (is (b/exists? (xpath "//span[contains(text(),'" excerpt "')]")))
          (is* (= [abstract] (b/get-elements-text
                              (xpath "//span[contains(text(),'" excerpt "')]"))))))))

(deftest-browser pdf-files
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [res-path "test-files/test-pdf-import"
   files (for [f (fs/list-dir (io/resource res-path))]
           (str res-path "/" (fs/base-name f)))]
  (do (nav/log-in (:email test-user))
      (nav/new-project "pdf files test")
      (b/select-datasource "PDF files")
      (b/wait-until-exists (xpath "//button[contains(text(),'browse files')]"))
      (b/uppy-attach-files files)
      (b/wait-until-exists (xpath "//button[contains(text(),'Upload')]"))
      (b/click (xpath "//button[contains(text(),'Upload')]"))
      (b/wait-until-exists "div.delete-button")
      ;;(b/init-route (-> (taxi/current-url) b/url->path))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.column.article-title" :displayed? true :delay 200)
      (b/exists? "div.pdf-container div.page div.canvasWrapper")
      (b/click ".ui.menu > .item.articles" :delay 100)
      (b/exists? "a.column.article-title")))
