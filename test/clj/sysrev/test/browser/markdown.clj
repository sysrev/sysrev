(ns sysrev.test.browser.markdown
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.test.core :as test]
            [sysrev.config.core :refer [env]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser happy-path-project-description
  true
  [input "textarea"
   project-name "Markdown Test"
   search-term "foo bar"
   create-project-description
   {:xpath "//div[contains(text(),'Create Project Description')]"}
   edit-markdown-icon {:xpath "//i[contains(@class,'pencil icon')]"}
   save-button {:xpath "//button[contains(text(),'Save')]"}
   disabled-save
   {:xpath "//button[contains(text(),'Save') and contains(@class,'disabled')]"}
   loading-save
   {:xpath "//button[contains(text(),'Save') and contains(@class,'loading')]"}
   click-save (fn []
                (Thread/sleep 200)
                (b/wait-until #(and (b/displayed-now? save-button)
                                    (not (taxi/exists? disabled-save))
                                    (not (taxi/exists? loading-save)))
                              2500)
                (b/click save-button)
                (b/wait-until-loading-completes :pre-wait 200))
   markdown-description "#foo bar\n##baz qux"
   edited-markdown-description (str markdown-description "\nquxx quzz corge")
   overview-tab {:xpath "//span[contains(text(),'Overview')]"}]
  (do (when (= (:profile env) :dev)
        (b/create-test-user))
      (nav/log-in)
      (nav/new-project project-name)
      (pm/add-articles-from-search-term search-term)
      (Thread/sleep 250)
;;; project description
      (b/click overview-tab)
      (b/wait-until-loading-completes :pre-wait 250)
      (b/click create-project-description)
      ;; enter markdown
      (b/wait-until-displayed input)
      (b/set-input-text input markdown-description :delay 100)
      (click-save)
      ;; check that the markdown exists
      (b/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (b/exists? {:xpath "//h2[contains(text(),'baz qux')]"}))
      ;; edit the markdown
      (b/click edit-markdown-icon)
      (b/wait-until-displayed input)
      (b/set-input-text input edited-markdown-description :delay 100)
      (click-save)
      (b/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (b/exists? {:xpath "//p[contains(text(),'quxx quzz corge')]"}))
      ;; delete the markdown, make sure we are back at stage one
      (b/click edit-markdown-icon)
      (Thread/sleep 100)
      ;; clear the text area
      (b/wait-until-displayed input)
      (Thread/sleep 100)
      (taxi/clear input)
      (Thread/sleep 100)
      (taxi/send-keys input org.openqa.selenium.Keys/ENTER)
      (Thread/sleep 100)
      (taxi/send-keys input org.openqa.selenium.Keys/BACK_SPACE)
      (Thread/sleep 100)
      (click-save)
      ;; a prompt for creating a project description
      (b/wait-until-exists create-project-description)
      (is (b/exists? create-project-description :wait? false)))

  :cleanup
  (do (nav/delete-current-project)
      (nav/log-out)))
