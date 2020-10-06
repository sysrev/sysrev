(ns sysrev.test.browser.markdown
  (:require [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.config :refer [env]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn click-save []
  (b/wait-until-loading-completes :pre-wait 50)
  (b/click ".markdown-component .ui.save-button"))

(deftest-browser happy-path-project-description
  true test-user
  [input ".markdown-component textarea"
   create-button ".project-description .ui.button.create-description"
   edit-icon ".project-description i.pencil.icon"
   content-first "#foo bar\n##baz qux"
   content-edit (str content-first "\nquxx quzz corge")
   test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Markdown Test")
      (pm/import-pubmed-search-via-db "foo bar")
;;; project description
      (b/click "#project a.item.overview")
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      (log/info "creating project description")
      (b/click create-button)
      ;; enter markdown
      (b/wait-until-displayed input)
      (b/set-input-text input content-first :delay 50)
      (click-save)
      ;; check that the markdown exists
      (b/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (b/exists? {:xpath "//h2[contains(text(),'baz qux')]"}))
      ;; edit the markdown
      (b/click edit-icon)
      (b/wait-until-displayed input)
      ;; make sure textarea contains the previously saved markdown
      (is (b/exists? (xpath "//textarea[text()='" content-first "']")))
      (b/set-input-text input content-edit :delay 50)
      (click-save)
      (b/wait-until-displayed {:xpath "//h1[contains(text(),'foo bar')]"})
      (is (b/exists? {:xpath "//p[contains(text(),'quxx quzz corge')]"}))
      ;; delete the markdown, make sure we are back at stage one
      (b/click edit-icon)
      ;; clear the text area
      (b/wait-until-displayed input)
      (taxi/clear input)
      (Thread/sleep 25)
      (taxi/send-keys input org.openqa.selenium.Keys/ENTER)
      (Thread/sleep 25)
      (taxi/send-keys input org.openqa.selenium.Keys/BACK_SPACE)
      (Thread/sleep 25)
      (click-save)
      ;; a prompt for creating a project description
      (b/displayed? create-button))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)))
