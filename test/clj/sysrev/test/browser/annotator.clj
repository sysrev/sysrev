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
    (try
      (let [project-name "Annotator Test"
            search-term "foo bar enthalpic mesoporous"
            article-title-div {:xpath "//div[contains(@class,'article-title')]"}
            enable-annotator-button {:xpath "//div[contains(@class,'button') and contains(text(),'Enable Sidebar')]"}
            review-annotator-tab {:xpath (str "//div[contains(@class,'review-interface')]"
                                              "//a[contains(text(),'Annotations')]")}
            select-text-to-annotate {:xpath "//div[contains(text(),'Select text to annotate')]"}
            selected-text {:xpath "//span[contains(text(),'Journal of the American Chemical Society')]"}
            semantic-class-input {:xpath "//div[contains(@class,'semantic-class')]//input"}
            semantic-class "foo"
            annotation-value-input {:xpath "//div[contains(@class,'value')]//input"}
            annotation-value "bar"
            submit-button {:xpath "//button[contains(@class,'positive')]"}
            blue-pencil-icon {:xpath "//i[contains(@class,'pencil')]"}]
        (nav/log-in)
;;;; create a project
        (nav/new-project project-name)
;;; add sources
        ;; create a new source
        (pm/add-articles-from-search-term search-term)
;;;; start annotating articles
        ;; review the single article result
        ;; note: if issues start arising with this part of the test
        ;; check to see that the search-term still returns only one result
        (b/click review-articles/review-articles-button)
        (b/wait-until-exists {:xpath "//div[@id='project_review']"})
        (review-articles/set-article-labels
         [(merge review-articles/include-label-definition
                 {:value true})])
        (b/wait-until-exists review-articles/no-articles-need-review)
        ;; select one article and annotate it
        (b/click review-articles/articles-button)
        (b/click article-title-div :delay 100)
        (b/click enable-annotator-button)
        (b/click review-annotator-tab)
        (b/wait-until-displayed select-text-to-annotate)
        (->actions @b/active-webdriver
                   (double-click (taxi/find-element @b/active-webdriver selected-text)))
        (b/input-text semantic-class-input semantic-class)
        (b/input-text annotation-value-input annotation-value)
        (b/click submit-button)
        (b/wait-until-exists blue-pencil-icon)
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
          (is (= 23 (get-in annotation [:context :end-offset])))))
      (finally
        (nav/delete-current-project)))))
