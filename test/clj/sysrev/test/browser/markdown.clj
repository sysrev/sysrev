(ns sysrev.test.browser.markdown
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [sysrev.test.browser.core :as browser :refer [deftest-browser]]
            [sysrev.test.browser.create-project :as project]
            [sysrev.test.browser.navigate :as navigate :refer [log-in log-out]]
            [sysrev.test.core :refer [default-fixture]]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

(deftest-browser happy-path-project-description
  (try
    (let [project-name "Markdown Test"
          search-term "foo bar"
          create-project-description {:xpath "//div[contains(text(),'Create Project Description')]"}
          edit-markdown-icon {:xpath "//i[contains(@class,'pencil icon')]"}
          save-button {:xpath "//button[contains(text(),'Save')]"}
          markdown-description "#foo bar\n##baz qux"
          edited-markdown-description (str markdown-description "\nquxx quzz corge")
          overview-tab {:xpath "//span[contains(text(),'Overview')]"}]
      (log-in)
;;; create a project
      (browser/go-route "/select-project")
      (browser/set-input-text {:xpath "//input[@placeholder='Project Name']"}
                              project-name)
      (browser/click {:xpath "//button[text()='Create']"} :delay 200)
      (browser/wait-until-displayed project/project-title-xpath)
      (is (string/includes? (taxi/text project/project-title-xpath) project-name))
;;; add sources
      ;; create a new source
      (project/add-articles-from-search-term search-term)
      ;; check that there is one article source listed
      (taxi/wait-until #(= 1 (count (taxi/find-elements project/article-sources-list-xpath)))
                       10000 75)
;;; project description
      ;; create project description
      (navigate/wait-until-overview-ready)
      (browser/click overview-tab)
      (browser/click create-project-description)
      ;; enter markdown
      (browser/set-input-text {:xpath "//textarea"} markdown-description)
      (browser/click save-button)
      ;; check that the markdown exists
      (browser/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (browser/exists? {:xpath "//h2[contains(text(),'baz qux')]"}))
      ;; edit the markdown
      (browser/click {:xpath "//div[@id='project-description']"})
      (browser/click edit-markdown-icon)
      (browser/wait-until-displayed {:xpath "//textarea"})
      (taxi/clear {:xpath "//textarea"})
      (browser/set-input-text {:xpath "//textarea"} edited-markdown-description)
      (browser/click save-button)
      (browser/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (browser/exists? {:xpath "//p[contains(text(),'quxx quzz corge')]"}))
      ;; delete the markdown, make sure we are back at stage one
      (browser/click {:xpath "//div[@id='project-description']"})
      (browser/click edit-markdown-icon)
      ;; clear the text area
      (taxi/clear {:xpath "//textarea"})
      (taxi/send-keys {:xpath "//textarea"}
                      org.openqa.selenium.Keys/ENTER)
      (taxi/send-keys {:xpath "//textarea"}
                      org.openqa.selenium.Keys/BACK_SPACE)
      (browser/click save-button)
      ;; a prompt for creating a project description
      (is (browser/exists? create-project-description)))
    (finally
      (project/delete-current-project)
      (log-out))))
