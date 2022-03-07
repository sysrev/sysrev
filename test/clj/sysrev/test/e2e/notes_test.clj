(ns sysrev.test.e2e.notes-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.api :as api]
   [sysrev.source.interface :as src]
   [sysrev.project.member :as member]
   [sysrev.test.core :as test]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.labels :as labels]))

(deftest ^:e2e test-notes
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [content "this is a note"
          {:keys [user-id] :as user} (test/create-test-user system)
          {:keys [project-id]} (:project (api/create-project-for-user!
                                          (:web-server system)
                                          "Browser Test (annotation labels)" user-id true))]
      (member/add-project-member
       project-id user-id :permissions ["admin" "member"])
      (src/import-source
       (select-keys system [:web-server])
       :pmid-vector
       project-id
       {:pmids [25706626 25215519 23790141 22716928 19505094 9656183]}
       {:use-future? false})
      (doto test-resources
        (account/log-in user))
      (e/go-project test-resources project-id "/review")
      (doto driver
        (labels/set-label-answer! (assoc labels/include-label-definition :value true))
        (ea/scroll-bottom)
        (et/fill-visible {:css ".field.notes textarea"} content)
        (et/is-click-visible {:css ".button.save-labels"})
        e/wait-until-loading-completes)
      (e/go-project test-resources project-id "/articles")
      (doto driver
        (et/click-visible {:css ".column.label_notes button"})
        (et/is-wait-visible {:fn/has-text content})
        (et/click-visible {:css ".article-title"})
        (et/is-wait-visible {:fn/has-text content})))))
