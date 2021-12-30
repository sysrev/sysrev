(ns sysrev.test.e2e.markdown-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.api :as api]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.source.import :as import]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e test-happy-path-project-description
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          {:keys [project]} (api/create-project-for-user!
                             "Markdown Test" user-id true)
          {:keys [project-id]} project
          description-first "#foo bar\n##baz qux"
          description-edit "\nquxx quzz corge"
          create-description-button {:css (str ".project-description .ui.button.create-description:"
                                               e/not-disabled)}
          description-input {:css ".markdown-component textarea"}
          edit-icon {:css (str ".project-description i.pencil.icon:" e/not-disabled)}
          save-button {:css (str ".project-description .markdown-component .ui.save-button:"
                                 e/not-disabled)}]
      (import/import-pmid-vector
       (select-keys system [:web-server])
       project-id
       {:pmids [33222245 32891636 25706626 25215519 23790141 22716928 19505094 9656183]}
       {:use-future? false})
      (doto test-resources
        (account/log-in user)
        (e/go (str "/p/" project-id)))
      (doto driver
        (et/is-click-visible {:css "#project a.item.overview"})
        e/wait-until-loading-completes
        ;; Enter description markdown
        (et/is-click-visible create-description-button)
        (et/is-wait-visible description-input)
        (ea/fill description-input description-first)
        ;; Save description
        (et/is-click-visible save-button)
        ;; Check that the markdown exists
        (et/is-wait-visible "//h1[contains(text(),'foo bar')]")
        (et/is-wait-visible "//h2[contains(text(),'baz qux')]")
        ;; Edit the markdown
        (et/is-click-visible edit-icon)
        (et/is-wait-visible description-input)
        ;; Make sure textarea contains the previously saved markdown
        (et/is-wait-visible (str "//textarea[text()='" description-first "']"))
        (ea/fill description-input description-edit)
        (et/is-click-visible save-button)
        (et/is-wait-visible "//h1[contains(text(),'foo bar')]")
        (et/is-wait-visible "//p[contains(text(),'quxx quzz corge')]")
        ;; delete the markdown, make sure we are back at stage one
        (et/is-click-visible edit-icon)
        ;; clear the text area
        (et/is-wait-visible description-input)
        (et/clear description-input)
        (et/is-click-visible save-button)
        ;; a prompt for creating a project description
        (et/is-wait-visible create-description-button)))))
