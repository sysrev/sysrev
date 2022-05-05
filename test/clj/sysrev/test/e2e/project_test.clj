(ns sysrev.test.e2e.project-test
  (:require [clojure.test :refer :all]
            [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member]
            [sysrev.test.core :as test]
            [sysrev.test.e2e.account :as account]
            [sysrev.test.e2e.core :as e]
            [sysrev.test.e2e.project :as e-project]
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
            (et/is-fill-visible {:css "#create-project .project-name input"} "test-user-create-new")
            (et/is-click-visible (str "//p[contains(text(),'Private')]"
                                      "/ancestor::div[contains(@class,'row')]"
                                      "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
            (et/is-click-visible "//button[contains(text(),'Create Project')]")))
        (testing "project is private"
          (doto driver
            (et/is-wait-visible {:css "i.grey.lock"})
            (et/is-wait-visible "//span[contains(text(),'Private')]")))))))

(defmacro test-project-route-panel [test-resources project-relative-url panel]
  `(let [test-resources# ~test-resources]
     (e/go-project test-resources# (:project-id test-resources#) ~project-relative-url)
     (et/is-wait-visible (:driver test-resources#) {:id (e/panel-name ~panel)})))

(deftest ^:e2e test-project-routes
  (e/with-test-resources [{:keys [system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [project-id (e-project/create-project! test-resources "test-project-routes")]
      (doto (assoc test-resources :project-id project-id)
        (test-project-route-panel "" [:project])
        (test-project-route-panel "/add-articles" [:project :project :add-articles])
        (test-project-route-panel "/labels/edit" [:project :project :labels :edit])
        (test-project-route-panel "/settings" [:project :project :settings])
        (test-project-route-panel "/export" [:project :project :export-data])
        (test-project-route-panel "/review" [:project :review])
        (test-project-route-panel "/articles" [:project :project :articles])))))

(deftest ^:e2e test-private-project-downgrade
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources (str "Baz-Qux-" (util/random-id)))]
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (e/go-project test-resources project-id "/settings")
      (doto driver
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
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources (str "Baz-Qux-" (util/random-id)))]
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (e/go-project test-resources project-id "/settings")
      (doto driver
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

(def valid-gengroups
  [{:name "English" :description "Group for the English language"}
   {:name "Spanish" :description "Group for the Spanish language"}
   {:name "Japanese" :description "Group for the Japanese language"}])

(deftest ^:e2e test-gengroups-crud
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [project-id (e-project/create-project! test-resources "test-gengroups-crud")]
      (e/go-project test-resources project-id "/users")
      (testing "creating gengroups works"
        (doseq [{:keys [description name]} valid-gengroups]
          (doto driver
            (et/is-click-visible :new-gengroup-btn)
            (et/is-fill-visible :gengroup-name-input name)
            (et/is-fill-visible :gengroup-description-input description)
            (et/is-click-visible :create-gengroup-btn)
            (et/is-wait-visible {:css ".alert-message.success"}))))
      (testing "editing gengroups works"
        (let [{:keys [description name]} (first valid-gengroups)]
          (doto driver
            (et/is-click-visible {:css (format ".edit-gengroup-btn[data-gengroup-name='%s']" name)})
            (et/clear-visible :gengroup-name-input)
            (et/is-fill-visible :gengroup-name-input (str name " - edit"))
            (et/clear-visible :gengroup-description-input)
            (et/is-fill-visible :gengroup-description-input (str description " - edit"))
            (et/is-click-visible :save-gengroup-btn)
            (et/is-wait-visible {:css ".alert-message.success"}))))
      (testing "deleting gengroups works"
        (let [{:keys [name]} (second valid-gengroups)]
          (doto driver
            (et/is-click-visible {:css (format ".delete-gengroup-btn[data-gengroup-name='%s']" name)})
            (et/is-click-visible :delete-gengroup-confirmation-btn)
            (et/is-wait-visible {:css ".alert-message.success"})))))))

(deftest ^:e2e test-gengroups-assign
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [project-id (e-project/create-project! test-resources "test-gengroups-assign")
          {:keys [description name]} (first valid-gengroups)]
      (e-project/create-project-member-gengroup! test-resources project-id name description)
      (e/go-project test-resources project-id "/users")
      (testing "can add a user to a gengroup"
        (doto driver
          (et/is-click-visible {:css ".manage-member-btn"})
          (et/is-wait-visible :search-gengroups-input)
          (ea/fill-human :search-gengroups-input name {:mistake-prob 0 :pause-max 0.01})
          (et/is-click-visible {:css (format ".result[name='%s']" name)})
          (et/is-click-visible :add-gengroup-btn)
          (et/is-wait-visible {:css ".alert-message.success"}))))))

(deftest ^:e2e test-clone-project
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [username] :as user} (test/create-test-user system)
          _ (account/log-in test-resources user)
          project-id (e-project/create-project! test-resources "test-clone-project")]
      (e/go-project test-resources project-id)
      (testing "username displays correctly"
        (doto driver
          (et/is-click-visible :clone-button)
          (et/is-wait-visible [{:fn/has-class :clone-project}
                               {:fn/has-text username}]))))))

(deftest ^:e2e test-project-users
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id username]} (test/create-test-user system)
          {:keys [project-id]} (project/create-project "test-project-users")]
      (member/add-project-member project-id user-id)
      (e/go-project test-resources project-id "/users")
      (testing "usernames are correct"
        (et/is-wait-visible driver {:fn/has-text username})))))
