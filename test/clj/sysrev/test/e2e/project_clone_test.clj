(ns sysrev.test.e2e.project-clone-test
  (:require
   [clojure.test :refer :all]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.project.core :as project]
   [sysrev.source.interface :as src]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as e-project]
   [sysrev.util :as util]))

(deftest ^:e2e test-clone-project
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [project-id (e-project/create-project! test-resources "test-clone-project-happy-path")]
      (src/import-source
       (select-keys system [:web-server])
       :pmid-vector
       project-id
       {:pmids [33222245 32891636 25706626]}
       {:use-future? false})
      (e/go-project test-resources project-id)
      (testing "Users can clone projects"
        (doto driver
          (et/is-click-visible :clone-button)
          (et/is-click-visible :clone-to-user)
          e/wait-until-loading-completes)
        (let [cloned-project-id (e-project/current-project-id driver)]
          (is (pos-int? cloned-project-id))
          (is (not= project-id cloned-project-id))))
      (testing "Cloned projects have the same articles as the original project"
        (e/go-project test-resources (e-project/current-project-id driver) "/articles")
        (doto driver
          (et/is-click-visible {:fn/has-class :article-title
                                :fn/has-text "Regulatory challenges with biosimilars"})
          (et/is-wait-visible {:fn/has-text "The World Health Organization (WHO) issued guidelines"}))))))

(deftest ^:e2e test-clone-login-redirect
  (e/with-test-resources [{:keys [driver] :as test-resources} {}]
    (let [{:keys [project-id]} (project/create-project "test-clone-login-redirect")]
      (testing "Anonymous users trying to clone a project are prompted to log in, and can then immediately clone the project"
        (e/go-project test-resources project-id)
        (doto driver
          (et/is-click-visible :clone-button)
          (et/is-fill-visible :login-email-input (format "foo+%s@qux.com" (util/random-id)))
          (et/is-fill-visible :login-password-input "foobar")
          (et/is-click-visible :login-submit-button)
          (et/is-click-visible :clone-to-user)
          (et/is-wait-visible :project))))))

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/clone.clj#L171
(deftest ^:kaocha/pending ^:e2e test-group-label-clone)
