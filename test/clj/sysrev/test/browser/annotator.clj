(ns sysrev.test.browser.annotator
  (:require [clj-webdriver.taxi :as taxi]
            [clj-webdriver.core :refer [->actions double-click]]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [sysrev.api :as api]
            [sysrev.db.users :as users]
            [sysrev.test.browser.core :as browser :refer [deftest-browser]]
            [sysrev.test.browser.create-project :as project]
            [sysrev.test.browser.navigate :refer [log-in log-out]]
            [sysrev.test.browser.review-articles :as review-articles]
            [sysrev.test.core :refer [default-fixture]]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

(deftest-browser happy-path-project-annotator
  (when (browser/db-connected?)
    (try
      (let [project-name "Annotator Test"
            search-term "foo bar enthalpic mesoporous"
            article-title-div {:xpath "//div[contains(@class,'article-title')]"}
            enable-annotator-button {:xpath "//div[contains(@class,'button') and contains(text(),'Enable Annotator')]"}
            select-text-to-annotate {:xpath "//div[contains(text(),'Select text to annotate')]"}
            selected-text {:xpath "//span[contains(text(),'Journal of the American Chemical Society')]"}
            semantic-class-input {:xpath "//div[contains(@class,'semantic-class')]//input"}
            semantic-class "foo"
            annotation-value-input {:xpath "//div[contains(@class,'value')]//input"}
            annotation-value "bar"
            submit-button {:xpath "//button[contains(@class,'positive')]"}
            blue-pencil-icon {:xpath "//i[contains(@class,'pencil')]"}]
        (log-in)
;;;; create a project
        (browser/go-route "/select-project")
        (browser/set-input-text {:xpath "//input[@placeholder='Project Name']"} project-name)
        (browser/click {:xpath "//button[text()='Create']"} :delay 200)
        (browser/wait-until-displayed project/project-title-xpath)
        (is (string/includes? (taxi/text project/project-title-xpath) project-name))
;;; add sources
        ;; create a new source
        (project/add-articles-from-search-term search-term)
        ;; check that there is one article source listed
        (taxi/wait-until #(= 1 (count (taxi/find-elements project/article-sources-list-xpath)))
                         10000 75)
;;;; start annotating articles
        ;; review the single article result
        ;; note: if issues start arising with this part of the test
        ;; check to see that the search-term still returns only one result
        (browser/click review-articles/review-articles-button)
        (browser/wait-until-exists {:xpath "//div[@id='project_review']"})
        (review-articles/set-article-labels
         [(merge review-articles/include-label-definition
                 {:value true})])
        (browser/wait-until-exists review-articles/no-articles-need-review)
        ;; select one article and annotate it
        (browser/click review-articles/articles-button)
        (browser/click article-title-div)
        (browser/wait-until-displayed enable-annotator-button)
        (browser/click enable-annotator-button)
        (browser/wait-until-displayed select-text-to-annotate)
        (->actions @browser/active-webdriver
                   (double-click (taxi/find-element @browser/active-webdriver selected-text)))
        (browser/wait-until-exists semantic-class-input)
        (taxi/input-text semantic-class-input semantic-class)
        (taxi/input-text annotation-value-input annotation-value)
        (browser/wait-until-exists submit-button)
        (browser/click submit-button)
        (browser/wait-until-exists blue-pencil-icon)
        ;;check the annotation
        (let [{:keys [email password]} browser/test-login
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
          (is (= 23 (get-in annotation [:context :end-offset])))))
      (finally
        (project/delete-current-project)
        (log-out)))))
