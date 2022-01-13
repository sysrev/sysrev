(ns sysrev.test.e2e.project-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.group.core :as group]
   [sysrev.source.import :as import]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as project]
   [sysrev.util :as util]))

(deftest ^:e2e test-user-create-new
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)]
      (account/log-in test-resources user)
      (testing "private setting is disabled for basic plan user"
        (doto driver
          (et/is-click-visible {:css "#new-project.button"})
          (et/is-wait-visible [{:fn/has-class :public-or-private}
                               {:fn/has-classes [:disabled :radio]}])))
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (testing "private projects work for unlimited plan user"
        (ea/refresh driver)
        (testing "create private project"
          (doto driver
            (et/is-fill-visible {:css "#create-project .project-name input"} "SysRev Browser Test (test-user-create-new)")
            (et/is-click-visible (str "//p[contains(text(),'Private')]"
                                  "/ancestor::div[contains(@class,'row')]"
                                  "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
            (et/is-click-visible "//button[contains(text(),'Create Project')]")))
        (testing "project is private"
          (doto driver
            (et/is-wait-visible {:css "i.grey.lock"})
            (et/is-wait-visible "//span[contains(text(),'Private')]")))))))

(deftest ^:e2e test-group-create-new
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          group-name (str "Bravo" (util/random-id))
          group-id (group/create-group! group-name)]
      (group/add-user-to-group! user-id group-id :permissions ["owner"])
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (account/log-in test-resources user)
      (doto driver
        (et/is-click-visible :user-name-link)
        (et/is-click-visible :user-orgs)
        (et/is-click-visible (str "//a[text()='" group-name "']"))
        (et/is-click-visible :new-project)
        ;; create the private project
        (et/is-fill-visible {:css "#create-project .project-name input"}
                            "SysRev Browser Test (test-group-create-new)")
        (et/is-click-visible (str "//p[contains(text(),'Private')]"
                                  "/ancestor::div[contains(@class,'row')]"
                                  "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
        (et/is-click-visible "//button[contains(text(),'Create Project')]")
        ;; is this project private?
        (et/is-wait-visible {:css "i.grey.lock"})
        (et/is-wait-visible "//span[contains(text(),'Private')]")))))

(deftest ^:e2e test-private-project-downgrade
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          project-name (str "Baz Qux " (util/random-id))]
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (account/log-in test-resources user)
      (project/create-project! test-resources project-name)
      (doto driver
        (et/is-click-visible {:fn/has-text "Settings"})
        (et/is-click-visible :public-access_private)
        (et/is-click-visible :save-options)
        e/wait-until-loading-completes)
      (testing "paywall is in place for private projects after user downgrades plan"
        (test/change-user-plan! system user-id "Basic")
        (doto driver
          ea/refresh
          (et/is-wait-visible {:fn/has-text "This private project is currently inaccessible"})
          ;; this is a user project, should link to /user/plans
          (et/is-wait-visible "//a[contains(@href,'/user/plans')]")))
      (testing "making project public again works"
        (doto driver
          (et/is-click-visible {:fn/has-class :set-publicly-viewable})
          (et/is-click-visible {:fn/has-class :confirm-cancel-form-confirm})
          (et/is-wait-visible {:fn/has-text "Label Definitions"}))))))

(deftest ^:e2e test-private-project-plan-upgrade
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          project-name (str "Baz Qux " (util/random-id))]
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (account/log-in test-resources user)
      (project/create-project! test-resources project-name)
      (doto driver
        (et/is-click-visible {:fn/has-text "Settings"})
        (et/is-click-visible :public-access_private)
        (et/is-click-visible :save-options)
        e/wait-until-loading-completes)
      (testing "paywall is in place for private projects after user downgrades plan"
        (test/change-user-plan! system user-id "Basic")
        (doto driver
          ea/refresh
          (et/is-wait-visible {:fn/has-text "This private project is currently inaccessible"})
          ;; this is a user project, should link to /user/plans
          (et/is-wait-visible "//a[contains(@href,'/user/plans')]")))
      (testing "paywall is lifted after user upgrades plan"
        (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
        (doto driver
          ea/refresh
          (et/is-wait-visible {:fn/has-text "Label Definitions"}))))))

(deftest ^:e2e test-article-search
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user (test/create-test-user system)
          _ (account/log-in test-resources user)
          project-id (project/create-project! test-resources (str "Test Article Search " (util/random-id)))
          article-count (fn [driver]
                          (count (ea/query-all driver {:css ".article-list-segments .article-list-article"})))]
      (import/import-pmid-vector
       (select-keys system [:web-server])
       project-id
       {:pmids [33222245 32891636 25706626 25215519 23790141 22716928 19505094 9656183]}
       {:use-future? false})
      (e/go-project test-resources project-id "/articles")
      (testing "filtering by search terms"
        (et/is-fill-visible driver :article-search "CO2")
        (e/wait-until-loading-completes driver)
        (is (= 3 (article-count driver)))
        (et/is-fill-visible driver :article-search " earth")
        (e/wait-until-loading-completes driver)
        (is (= 1 (article-count driver))))
      (testing "clearing the search input shows all articles"
        (et/clear driver :article-search)
        (e/wait-until-loading-completes driver)
        (is (= 8 (article-count driver)))))))
