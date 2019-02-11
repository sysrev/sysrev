(ns sysrev.test.browser.annotator
  (:require [clj-webdriver.taxi :as taxi]
            [clj-webdriver.core :refer [->actions double-click]]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [sysrev.api :as api]
            [sysrev.db.users :as users]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.review-articles :as review-articles]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser happy-path-project-annotator
  (when (test/db-connected?)
    (let [project-name "Annotator Test"
          search-term "foo bar enthalpic mesoporous"
          article-title-div {:xpath "//div[contains(@class,'article-title')]"}
          select-text-to-annotate {:xpath "//div[contains(text(),'Select text to annotate')]"}
          selected-text {:xpath "//span[contains(text(),'Journal of the American Chemical Society')]"}
          semantic-class-input {:xpath "//div[contains(@class,'semantic-class')]//input"}
          semantic-class "foo"
          annotation-value-input {:xpath "//div[contains(@class,'value')]//input"}
          annotation-value "bar"
          submit-button {:xpath "//button[contains(@class,'positive')]"}
          blue-pencil-icon {:xpath "//i[contains(@class,'pencil')]"}]
      (nav/log-in)
      (nav/new-project project-name)
      (pm/add-articles-from-search-term search-term)
;;;; start annotating articles
      ;; review the single article result
      ;; note: if issues start arising with this part of the test
      ;; check to see that the search-term still returns only one result
      (b/click review-articles/review-articles-button)
      (b/wait-until-exists {:xpath "//div[@id='project_review']"})
      (review-articles/set-article-answers
       [(merge review-articles/include-label-definition
               {:value true})])
      (b/wait-until-exists review-articles/no-articles-need-review)
      ;; select one article and annotate it
      (nav/go-project-route "/articles")
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click article-title-div :delay 200)
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click x/enable-sidebar-button
               :if-not-exists :skip :delay 100)
      (Thread/sleep 100)
      (b/click x/review-annotator-tab)
      (b/wait-until-displayed select-text-to-annotate)
      (b/wait-until-displayed selected-text)
      (Thread/sleep 100)
      (->actions @b/active-webdriver
                 (double-click (taxi/find-element @b/active-webdriver selected-text)))
      (Thread/sleep 100)
      (b/input-text semantic-class-input semantic-class :delay 50)
      (b/input-text annotation-value-input annotation-value :delay 50)
      (b/click submit-button)
      (Thread/sleep 50)
      (b/wait-until-exists blue-pencil-icon)
      (Thread/sleep 250)
      ;;check the annotation
      (let [{:keys [email password]} b/test-login
            user-id (:user-id (users/get-user-by-email email))
            project-id (review-articles/get-user-project-id user-id)
            annotations (api/project-annotations project-id)
            annotation (first annotations)]
        
        (is (= (count annotations) 1))
        (is (= semantic-class (:semantic-class annotation)))
        (is (= annotation-value (:annotation annotation)))
        (is (= "Journal of the American Chemical Society"
               (get-in  annotation [:context :text-context])))
        (is (= 15 (get-in annotation [:context :start-offset])))
        (is (= 23 (get-in annotation [:context :end-offset]))))))

  :cleanup
  (when (test/db-connected?)
    (nav/delete-current-project)))
