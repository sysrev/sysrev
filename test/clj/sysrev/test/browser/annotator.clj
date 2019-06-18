(ns sysrev.test.browser.annotator
  (:require [clj-webdriver.taxi :as taxi]
            [clj-webdriver.core :refer
             [->actions double-click move-to-element click-and-hold move-by-offset release perform]]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure-csv.core :as csv]
            [sysrev.api :as api]
            [sysrev.db.users :as users]
            [sysrev.db.export :as export]
            [sysrev.source.import :as import]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review-articles]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.shared.util :as sutil :refer [in?]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def article-title-div (xpath "//a[contains(@class,'article-title')]"))
(def select-text-to-annotate (xpath "//div[contains(text(),'Select text to annotate')]"))
(def blue-pencil-icon (xpath "//i[contains(@class,'pencil')]"))
(def submit-button (xpath "//button[contains(@class,'positive')]"))
(def semantic-class-input (xpath "//div[contains(@class,'semantic-class')]//input"))
(def annotation-value-input (xpath "//div[contains(@class,'value')]//input"))

(defn annotate-article [semantic-class annotation-value &
                        {:keys [offset-x] :or {offset-x 671}}]
  (b/wait-until-loading-completes :pre-wait 200)
  (b/click ".ui.button.change-labels" :if-not-exists :skip)
  (Thread/sleep 100)
  (b/click x/review-annotator-tab)
  (b/wait-until-displayed select-text-to-annotate)
  (Thread/sleep 100)
  (->actions @b/active-webdriver
             (move-to-element (taxi/element (xpath "//div[@data-field='primary-title']")) 0 0)
             (click-and-hold) (move-by-offset offset-x 0) (release) (perform))
  (Thread/sleep 100)
  (b/input-text semantic-class-input semantic-class :delay 50)
  (b/input-text annotation-value-input annotation-value :delay 50)
  (b/click submit-button)
  (Thread/sleep 50)
  (b/wait-until-exists blue-pencil-icon)
  (Thread/sleep 100))

(deftest-browser happy-path-project-annotator
  (test/db-connected?)
  [project-name "Annotator Test"
   selected-text {:xpath "//span[contains(text(),'Journal of the American Chemical Society')]"}
   semantic-class "foo"
   annotation-value "bar"
   project-id (atom nil)]
  (do (nav/log-in)
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      #_ (pm/add-articles-from-search-term "foo bar enthalpic mesoporous")
      ;; This doesn't work against staging.sysrev.com - cache not cleared?
      (pm/import-pmids-via-db [25215519])
      #_ (import/import-pmid-vector @project-id {:pmids [25215519]} {:use-future? false})
      #_ (b/init-route (str "/p/" @project-id "/add-articles"))
;;;; start annotating articles
      ;; review the single article result
      (b/click (x/project-menu-item :review))
      (b/wait-until-exists "div#project_review")
      (review-articles/set-article-answers
       [(merge review-articles/include-label-definition {:value true})])
      (b/wait-until-exists ".no-review-articles")
      ;; select one article and annotate it
      (nav/go-project-route "/articles")
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click article-title-div :delay 200)
      (annotate-article semantic-class annotation-value)
      ;; (b/wait-until-loading-completes :pre-wait 200)
      ;; (b/click article-title-div :delay 200)
      ;; (b/wait-until-loading-completes :pre-wait 200)
      ;; (b/click x/enable-sidebar-button
      ;;          :if-not-exists :skip :delay 100)
      ;; (Thread/sleep 100)
      ;; (b/click x/review-annotator-tab)
      ;; (b/wait-until-displayed select-text-to-annotate)
      ;; (b/wait-until-displayed selected-text)
      ;; (Thread/sleep 100)
      ;; (->actions @b/active-webdriver
      ;;            (move-to-element (taxi/element {:xpath "//div[@data-field='primary-title']"}) 0 0)
      ;;            (click-and-hold) (move-by-offset 671 0) (release) (perform))
      ;; (Thread/sleep 100)
      ;; (b/input-text semantic-class-input semantic-class :delay 50)
      ;; (b/input-text annotation-value-input annotation-value :delay 50)
      ;; (b/click submit-button)
      ;; (Thread/sleep 50)
      ;; (b/wait-until-exists blue-pencil-icon)
      ;; (Thread/sleep 250)
      ;;check the annotation
      (let [{:keys [email password]} b/test-login
            user-id (:user-id (users/get-user-by-email email))
            project-id (review-articles/get-user-project-id user-id)
            article-id (first (sysrev.db.project/project-article-ids project-id))
            {:keys [annotations]} (api/user-defined-annotations article-id)
            annotation (first annotations)
            annotations-csv (rest (export/export-annotations-csv project-id))
            [csv-row] annotations-csv]
        (is (= (count annotations) 1))
        (is (= semantic-class (:semantic-class annotation)))
        (is (= annotation-value (:annotation annotation)))
        (is (= (get-in annotation [:context :text-context :field]) "primary-title"))
        (is (= (get-in annotation [:context :client-field]) "primary-title"))
        (is (= 0 (get-in annotation [:context :start-offset])))
        (is (= 94 (get-in annotation [:context :end-offset])))
        ;; do we have highlights?
        (is (= (taxi/text (taxi/element "span.annotated-text"))
               "Important roles of enthalpic and entropic contributions to CO2 capture from simulated flue gas"))
        ;; check annotations csv export
        (is (= 1 (count annotations-csv)))
        (is (in? csv-row annotation-value))
        (is (in? csv-row semantic-class))
        (is (in? csv-row "primary-title"))
        (is (in? csv-row "0"))
        (is (in? csv-row "94"))
        (is (= annotations-csv (-> (csv/write-csv annotations-csv)
                                   (csv/parse-csv :strict true))))))
  :cleanup
  (nav/delete-current-project))
