(ns sysrev.test.e2e.org-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.group.core :as group]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as project]
   [sysrev.test.e2e.query :as q]
   [sysrev.util :as util]))

(defn change-user-permission-dropdown-xpath [username]
  (str "//table[@id='org-user-table']/tbody/tr/td/a[text()='" username "']"
       "/ancestor::tr"
       "/td/div[contains(@class,'change-org-user')]"))

(defn add-user-to-org!
  "Must be in Organization Settings of the project to add user to"
  [driver username]
  (doto driver
    (et/click-visible :org-members)
    (et/click-visible :add-member-button)
    (ea/wait-visible :org-search-users-input)
    ;; fill should work here, but doesn't
    (ea/fill-human :org-search-users-input username {:mistake-prob 0 :pause-max 0.01})
    (et/click-visible :submit-add-member)
    e/wait-until-loading-completes))

(defn change-user-permission!
  "Set username to permission. Must be in Organization Settings of the
  org you wish to change permissions in. permission is either 'Owner',
  'Admin', or 'Member'."
  [driver username permission]
  (doto driver
    (et/click-visible (change-user-permission-dropdown-xpath username))
    (et/click-visible {:fn/has-text "Change role"})
    (et/click-visible (str "//label[contains(text(),'" permission "')]"
                           "/ancestor::h4" "//label"))
    (et/click-visible :org-change-role-button)))

(defn create-org! [driver org-name]
  (doto driver
    (et/click-visible :user-name-link)
    (et/click-visible :user-orgs)
    (ea/wait-visible :create-org-input)
    (ea/fill :create-org-input org-name)
    (et/click-visible :create-org-button)
    e/wait-until-loading-completes))

(defn create-project-org! [driver project-name]
  (doto driver
    (et/click-visible {:css "#new-project.button"})
    (ea/fill {:css "#create-project .project-name input"} project-name)
    (et/click-visible "//button[contains(text(),'Create Project')]")
    e/wait-until-loading-completes))

(defn switch-to-org! [driver org-name]
  (doto driver
    (et/click-visible :user-name-link)
    (et/click-visible :user-orgs)
    (et/click-visible (str "//a[text()='" org-name "']"))
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
    (let [{:keys [email user-id username] :as user} (test/create-test-user system)
          org-1-name (str "Foo Bar Inc. " (util/random-id))
          org-2-name (str "Baz Qux " (util/random-id))
          org-1-project-name (str "Foo Bar Article Reviews " (util/random-id))
          {user-1-id :user-id user-1-name :username :as user-1} (test/create-test-user system)]
      (account/log-in test-resources user)
      (testing "a person can create a org and they are automatically made owners"
        (doto driver
          (et/is-click-visible :user-name-link)
          (et/is-click-visible :user-orgs)
          (et/is-wait-visible :create-org-input)
          (ea/fill :create-org-input org-1-name)
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
        (doto test-resources
          account/log-out
          (account/log-in user))
        (doto driver
          (create-org! org-2-name)
          (et/is-click-visible :org-members))
        ;; should only be one user in this org
        (is (= {username {:name username :permission "owner"}}
               (org-user-table-entries driver)))
        ;; switch back to the other org. there are two users in this one
        (e/go test-resources (str "/org/" (group/group-name->id org-1-name) "/users"))
        (is (= 2 (count (org-user-table-entries driver)))))
      (testing "an owner can create projects for that org"
        (doto driver
          (et/is-click-visible :org-projects)
          (create-project-org! org-1-project-name)))
      (testing "admins cannot create projects for that org"
        (doto driver
          (switch-to-org! org-2-name)
          (add-user-to-org! user-1-name)
          (change-user-permission! user-1-name "Admin"))
        (account/log-out test-resources)
        (account/log-in test-resources user-1)
        (switch-to-org! driver org-1-name)
        (testing "org-users and projects links exists"
          (doto driver
            (et/is-wait-visible :org-members)
            (et/is-wait-visible :org-projects)))
        (testing "group projects exists, but not the create project input"
          (doto driver
            (et/is-click-visible :org-projects)
            (et/is-wait-exists :projects)
            (et/is-not-visible? :new-project))))
      (testing "duplicate orgs can't be created"
        (account/log-out test-resources)
        (account/log-in test-resources user)
        (doto driver
          (et/is-click-visible :user-name-link)
          (et/is-click-visible :user-orgs)
          (et/is-wait-visible :create-org-input)
          (ea/fill :create-org-input org-1-name)
          (et/is-click-visible :create-org-button)
          (et/is-wait-visible (q/error-message (str "An organization with the name '" org-1-name "' already exists.")))))
      (testing "blank orgs can't be created"
        (doto driver
          (et/clear :create-org-input)
          (et/click-visible :create-org-button)
          (et/is-wait-visible (q/error-message "Organization names can't be blank")))))))

(deftest ^:e2e test-user-project-plan
  (e/with-test-resources [{:keys [driver system] :as test-resources}]
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
