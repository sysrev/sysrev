(ns sysrev.test.browser.markdown
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.core :refer [default-fixture]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

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
      (nav/log-in)
      (nav/new-project project-name)
      (pm/add-articles-from-search-term search-term)
;;; project description
      (b/click overview-tab)
      (b/click create-project-description)
      ;; enter markdown
      (b/set-input-text {:xpath "//textarea"} markdown-description)
      (b/click save-button)
      ;; check that the markdown exists
      (b/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (b/exists? {:xpath "//h2[contains(text(),'baz qux')]"}))
      ;; edit the markdown
      (b/click {:xpath "//div[@id='project-description']"})
      (b/click edit-markdown-icon)
      (b/wait-until-displayed {:xpath "//textarea"})
      (taxi/clear {:xpath "//textarea"})
      (b/set-input-text {:xpath "//textarea"} edited-markdown-description)
      (b/click save-button)
      (b/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (b/exists? {:xpath "//p[contains(text(),'quxx quzz corge')]"}))
      ;; delete the markdown, make sure we are back at stage one
      (b/click {:xpath "//div[@id='project-description']"})
      (b/click edit-markdown-icon)
      ;; clear the text area
      (taxi/clear {:xpath "//textarea"})
      (taxi/send-keys {:xpath "//textarea"}
                      org.openqa.selenium.Keys/ENTER)
      (taxi/send-keys {:xpath "//textarea"}
                      org.openqa.selenium.Keys/BACK_SPACE)
      (b/click save-button)
      ;; a prompt for creating a project description
      (is (b/exists? create-project-description)))
    (finally
      (nav/delete-current-project)
      (nav/log-out))))
