(ns sysrev.test.e2e.user-profile-test
  (:require [clojure.test :refer :all]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.file.user-image :as user-image]
            [sysrev.group.core :as group]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test]
            [sysrev.test.e2e.account :as account]
            [sysrev.test.e2e.core :as e]
            [sysrev.test.e2e.labels :as labels]
            [sysrev.test.e2e.project :as e-project]))

(deftest ^:e2e test-user-introduction
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          introduction "I am the browser test"]
      (group/add-user-to-group! user-id (group/group-name->id "public-reviewer"))
      (e/go test-resources (str "/user/" user-id "/profile"))
      (testing "A user can edit their introduction"
        (doto driver
          (et/is-click-visible :edit-introduction)
          (et/is-fill-visible [{:fn/has-class :introduction} {:tag :textarea}] introduction)
          (et/is-click-visible {:css ".introduction .save-button"})
          (et/is-wait-visible [{:fn/has-class :introduction}
                               {:fn/has-class :markdown-content}
                               {:fn/has-text introduction}])))
      (testing "User introduction displays correctly for other users"
        (account/log-out test-resources)
        (e/go test-resources (str "/user/" user-id "/profile"))
        (doto driver
          (et/is-wait-visible [{:fn/has-class :introduction}
                               {:fn/has-class :markdown-content}
                               {:fn/has-text introduction}])
          (et/is-not-visible? :edit-introduction))))))

(deftest ^:e2e test-avatar
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))]
      (testing "Users can upload avatar images"
        (e/go test-resources (str "/user/" user-id "/profile"))
        (doto driver
          (et/is-click-visible :change-avatar)
          (e/dropzone-upload "test-files/demo-1.jpg")
          (et/is-click-visible :set-avatar)
          e/wait-until-loading-completes)
        ;; The hash is environment-dependent, so just check that it exists
        (et/is-wait-pred #(seq (:key (user-image/user-active-avatar-image user-id))))))))

(deftest ^:e2e test-project-activity
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [sr-context]} system
          user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources "test-project-activity-1")]
      (testing "User project activity displays correctly"
        (src/import-source
         sr-context :pmid-vector project-id
         {:pmids [25706626 25215519 23790141]}
         {:use-future? false})
        (e/go-project test-resources project-id "/review")
        (dotimes [_ 3]
          (doto driver
            (labels/set-label-answer! (assoc labels/include-label-definition :value true))
            (et/is-click-visible {:css ".button.save-labels"})
            e/wait-until-loading-completes))
        (e/go test-resources (str "/user/" user-id "/projects"))
        (doto driver
          (et/is-wait-visible [:public-projects {:fn/has-text "test-project-activity-1"}])
          (et/is-wait-visible [{:fn/has-class :user-activity-summary}
                               {:fn/has-class :articles-reviewed
                                :fn/has-text "3"}])
          (et/is-wait-visible [{:fn/has-class :user-activity-summary}
                               {:fn/has-class :labels-contributed
                                :fn/has-text "3"}])
          (et/is-wait-visible [{:fn/has-class :user-activity-content}
                               {:fn/has-class :articles-reviewed
                                :fn/has-text "3"}])
          (et/is-wait-visible [{:fn/has-class :user-activity-content}
                               {:fn/has-class :labels-contributed
                                :fn/has-text "3"}]))))))

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/user_profiles.clj#L336
(deftest ^:kaocha/pending ^:e2e test-verify-email-and-project-invite)
