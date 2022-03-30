(ns sysrev.test.e2e.org-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [etaoin.api :as ea]
            [sysrev.api :as api]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.group.core :as group]
            [sysrev.test.core :as test]
            [sysrev.test.e2e.account :as account]
            [sysrev.test.e2e.core :as e]
            [sysrev.test.e2e.query :as q]
            [sysrev.util :as util]))

(defn create-org! [{:keys [system]} org-name owner-id]
  (let [group-id (group/create-group! org-name)]
    (group/add-user-to-group! owner-id group-id :permissions ["owner"])
    group-id))

(defn create-project-org! [driver project-name]
  (doto driver
    (et/click-visible {:css "#new-project.button"})
    (et/fill-visible {:css "#create-project .project-name input"} project-name)
    (et/click-visible "//button[contains(text(),'Create Project')]")
    e/wait-until-loading-completes))

(defn org-user-table-entries [driver]
  (ea/wait-visible driver :org-user-table)
  (->> (ea/query-all driver [:org-user-table {:tag :tr}])
       (map (fn [el]
              (as-> (ea/get-element-text-el driver el) $
                (str/split $ #"\n")
                {:name (first $) :permission (second $)})))
       (util/index-by :name)))

(defn user-group-permission [user-id group-name]
  (group/user-group-permission user-id (group/group-name->id group-name)))

(deftest ^:e2e test-simple-org
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id username] :as user} (test/create-test-user system)
          org-1-name (str "Foo Bar Inc. " (util/random-id))
          org-2-name (str "Baz Qux " (util/random-id))
          {user-1-name :username :as user-1} (test/create-test-user system)]
      (account/log-in test-resources user)
      (testing "a person can create a org and they are automatically made owners"
        (doto driver
          (et/is-click-visible :user-name-link)
          (et/is-click-visible :user-orgs)
          (et/fill-visible :create-org-input org-1-name)
          (et/is-click-visible :create-org-button)
          (et/is-wait-visible {:css "#new-project.button"}))
        (is (some #{"owner"} (user-group-permission user-id org-1-name))))
      (testing "an owner can add a user to the org"
        (doto driver
          (et/is-click-visible :org-members)
          (et/is-click-visible :add-member-button)
          (et/is-wait-visible :org-search-users-input)
          ;; fill should work here, but doesn't
          (ea/fill-human :org-search-users-input user-1-name {:mistake-prob 0 :pause-max 0.01})
          (et/is-click-visible :submit-add-member)
          (et/is-wait-visible {:fn/has-text user-1-name})
          (et/is-wait-visible {:fn/has-class "change-org-user"}))
        (is (= "member" (-> driver org-user-table-entries (get user-1-name) :permission))))
      (testing "a member can't change permissions"
        (doto test-resources
          account/log-out
          (account/log-in user-1)
          (e/go (str "/org/" (group/group-name->id org-1-name) "/users")))
        (doto driver
          (et/is-wait-visible :org-user-table)
          (et/is-not-visible? {:fn/has-class "change-org-user"})))
      (testing "when an org is switched, the correct user list shows up"
        (let [org-id (create-org! test-resources org-2-name user-id)]
          (doto test-resources
            account/log-out
            (account/log-in user)
            (e/go (str "/org/" org-id "/users"))))
        ;; should only be one user in this org
        (is (= {username {:name username :permission "owner"}}
               (org-user-table-entries driver)))
        ;; switch back to the other org. there are two users in this one
        (e/go test-resources (str "/org/" (group/group-name->id org-1-name) "/users"))
        (is (= 2 (count (org-user-table-entries driver)))))
      (testing "duplicate orgs can't be created"
        (account/log-out test-resources)
        (account/log-in test-resources user)
        (doto driver
          (et/is-click-visible :user-name-link)
          (et/is-click-visible :user-orgs)
          (et/is-wait-visible :create-org-input)
          (et/fill-visible :create-org-input org-1-name)
          (et/is-click-visible :create-org-button)
          (et/is-wait-visible (q/error-message (str "An organization with the name '" org-1-name "' already exists.")))))
      (testing "blank orgs can't be created"
        (doto driver
          (et/clear :create-org-input)
          (et/click-visible :create-org-button)
          (et/is-wait-visible (q/error-message "Organization names can't be blank")))))))

(deftest ^:e2e test-private-setting-disabled
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          org-name (str "Foo Bar Inc. " (util/random-id))
          org-id (create-org! test-resources org-name user-id)
          project-name (str "Foo Bar Article Reviews " (util/random-id))]
      (account/log-in test-resources user)
      (e/go test-resources (str "/org/" org-id "/projects"))
      (testing "private setting is disabled"
        (doto driver
          (create-project-org! project-name)
          (et/is-click-visible :project-privacy-button)
          (et/is-wait-visible {:fn/has-class :disabled
                               :id :public-access_private}))))))

(deftest ^:e2e test-private-projects
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          org-name (str "Foo Bar Inc. " (util/random-id))
          org-id (create-org! test-resources org-name user-id)
          project-name (str "Foo Bar Article Reviews " (util/random-id))
          _ (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
          {{:keys [project-id]} :project} (api/create-project-for-org! project-name user-id org-id false)
          project-url (str "/o/" org-id "/p/" project-id)]
      (test/change-user-plan! system user-id "Basic")
      (account/log-in test-resources user)
      (testing "paywall is in place for private projects after user downgrades plan"
        (e/go test-resources project-url)
        (doto driver
          (et/is-wait-visible {:fn/has-text "This private project is currently inaccessible"})
          (et/is-wait-visible (str "//a[contains(@href,'/org/" org-id "/plans')]"))))
      (testing "making project public again works"
        (doto driver
          (et/is-click-visible {:fn/has-class :set-publicly-viewable})
          (et/is-click-visible {:fn/has-class :confirm-cancel-form-confirm})
          (et/is-wait-visible {:fn/has-text "Label Definitions"})))
      (testing "can make project private again after upgrading"
        (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
        (e/go test-resources (str project-url "/settings"))
        (doto driver
          (et/is-click-visible :public-access_private)
          (et/is-click-visible :save-options)
          e/wait-until-loading-completes
          (et/is-click-visible :project-title-project-link)
          (et/is-wait-visible {:fn/has-text "Label Definitions"}))))))

