(ns sysrev.test.browser.orgs
  (:require [clojure.string :as str]
            [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.group.core :as group]
            [sysrev.payment.stripe :as stripe]
            [sysrev.payment.plans :refer [user-current-plan]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :refer [xpath]]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.core :as test]
            [sysrev.util :as util :refer [index-by random-id]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;; pricing workflow elements
(def choose-team-pro-button (xpath "//a[contains(text(),'Choose Team Premium')]"))
(def continue-with-team-pro (xpath "//div[contains(text(),'Continue with Team Premium')]"))
(def create-organization (xpath "//span[contains(text(),'Create Organization')]"))
(def create-account (xpath "//h3[contains(text(),'Create a free account before moving on to team creation')]"))
(def create-team (xpath "//h3[contains(text(),'create a Sysrev organization for your team')]"))
(def enter-payment-information
  (xpath "//h3[contains(text(),'enter payment information')]"))

(defn change-user-permission-dropdown [username]
  (xpath "//table[@id='org-user-table']/tbody/tr/td/a[text()='" username "']"
         "/ancestor::tr"
         "/td/div[contains(@class,'change-org-user')]"))
(def change-role (xpath "//span[contains(text(),'Change role...')]"))
(def org-change-role-button "#org-change-role-button")
(def disabled-set-private-button (xpath "//button[@id='public-access_private' and contains(@class,'disabled')]"))
(def active-set-private-button (xpath "//button[@id='public-access_private' and contains(@class,'active')]"))
(def set-private-button "#public-access_private")
(def save-options-button "#save-options")
(defn user-group-permission
  [user-id group-name]
  (group/user-group-permission user-id (group/group-name->id group-name)))

(defn org-user-table-entries []
  ;; go to the user table
  (b/click "a#org-members")
  (b/wait-until-exists "#org-user-table")
  (->> (taxi/find-elements-under "#org-user-table" {:tag :tr})
       (mapv taxi/text)
       (mapv #(str/split % #"\n"))
       (mapv #(hash-map :name (first %) :permission (second %)))
       (index-by :name)))

(defn add-user-to-org
  "Must be in Organization Settings of the project to add user to"
  [username]
  (b/click "#org-members")
  (b/click "#add-member-button" :delay 400)
  (b/set-input-text-per-char "#org-search-users-input" username)
  (b/click "button#submit-add-member"))

(defn change-user-permission
  "Set username to permission. Must be in Organization Settings of the
  org you wish to change permissions in. permission is either 'Owner'
  or 'Member'."
  [username permission]
  (b/click (change-user-permission-dropdown username) :delay 200)
  (b/click change-role :delay 400)
  (b/click (xpath "//label[contains(text(),'" permission "')]"
                  "/ancestor::h4" "//label")
           :delay 300)
  (b/click org-change-role-button :delay 300)
  (log/infof "changed org user permission (%s => %s)"
             (pr-str username) (pr-str permission)))

(defn create-org [org-name]
  (b/click "#user-name-link")
  (b/click "#user-orgs")
  (b/set-input-text-per-char "#create-org-input" org-name)
  (b/click "#create-org-button")
  (b/wait-until-exists "#new-project.button")
  (b/wait-until-loading-completes :pre-wait 100)
  (log/infof "created org %s" (pr-str org-name)))

(defn create-project-org
  "Must be in the Organization Settings for the org for which the project is being created"
  [project-name]
  (b/click "#new-project.button")
  (b/set-input-text "#create-project .project-name input" project-name)
  (b/click (xpath "//button[contains(text(),'Create Project')]"))
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath "//div[contains(@class,'project-title')]"
          "//a[contains(text(),'" project-name "')]"))
  (b/wait-until-loading-completes :pre-wait true))

(defn switch-to-org
  "switch to org-name, must be in Organization Settings"
  [org-name & {:keys [silent]}]
  (b/click "#user-name-link")
  (b/click "#user-orgs")
  (b/click (xpath "//a[text()='" org-name "']"))
  (b/wait-until-exists "#org-members")
  (b/wait-until-loading-completes :pre-wait 50)
  (when-not silent
    (log/infof "switched to org %s" (pr-str org-name))))

(deftest-browser simple-org-tests
  ;; for some reason add-user-to-org is having problems with remote test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [org-name-1 (str "Foo Bar Inc. " (random-id))
   org-name-2 (str "Baz Qux " (random-id))
   org-name-1-project (str "Foo Bar Article Reviews " (random-id))
   {:keys [user-id email]} test-user
   user1 (b/create-test-user :email "foo@bar.com")
   user1-name (:username user1)
   user-role #(get-in (org-user-table-entries) [% :permission])]
  (do
    (nav/log-in (:email test-user))
    ;; a person can create a org and they are automatically made owners
    (create-org org-name-1)
    (is (some #{"owner"} (user-group-permission user-id org-name-1)))
;;; an owner can add a user to the org
    ;; add a user
    (add-user-to-org user1-name)
    (b/is-soon (= "member" (user-role user1-name)) 3000 50)
    ;;an owner can change permissions of a member
    (change-user-permission user1-name "Owner")
    (b/is-soon (= "owner" (user-role user1-name)) 3000 50)
    ;; only an owner can change permissions, not a member
    (change-user-permission user1-name "Member")
    (nav/log-in (:email user1))
    (b/click "#user-name-link")
    (b/click "#user-orgs")
    (b/click (xpath "//a[text()='" org-name-1 "']"))
    (b/is-soon (not (taxi/exists? (change-user-permission-dropdown (:username test-user)))))
    ;; an org is switched, the correct user list shows up
    (nav/log-in (:email test-user))
    (b/click "#user-name-link")
    ;; create a new org
    (create-org org-name-2)
    ;; should only be one user in this org
    (b/is-soon (= 1 (count (org-user-table-entries))) 3000 50)
    ;; switch back to the other org, there is two users in this one
    (switch-to-org org-name-1)
    (b/is-soon (= 2 (count (org-user-table-entries))) 3000 50)
    ;; only an owner or admin of an org can create projects for that org
    (b/click "#org-projects")
    (create-project-org org-name-1-project)
    ;; add user1 to Baz Qux as an owner
    (nav/go-route "/org/users")
    (switch-to-org org-name-2)
    (add-user-to-org user1-name)
    (change-user-permission user1-name "Owner")
    ;; log-in as user1 and see that they cannot create group projects
    (nav/log-in (:email user1))
    (b/click "#user-name-link")
    (b/click "#user-orgs")
    (b/click (xpath "//a[text()='" org-name-1 "']"))
    ;; org-users and projects links exists
    (b/is-soon (and (taxi/exists? "#org-members") (taxi/exists? "#org-projects")))
    ;; group projects exists, but not the create project input
    (b/click "#org-projects")
    (b/exists? "#projects")
    (b/is-soon (not (taxi/exists? "form.create-project")))
    ;; user can't change permissions
    (b/is-soon (not (taxi/exists? (change-user-permission-dropdown (:username test-user)))))
    ;; switch to org-name-2
    (switch-to-org org-name-2)
    ;; user can create projects here
    (b/click "#org-projects")
    (b/wait-until-exists "#new-project.button")
    ;; can change user permissions for browser+test
    (b/click "#org-members")
    (b/wait-until-exists (change-user-permission-dropdown (:username test-user)))
    ;; duplicate orgs can't be created
    (nav/log-in (:email test-user))
    (b/click "#user-name-link")
    (b/click "#user-orgs")
    (b/set-input-text-per-char "#create-org-input" org-name-1)
    (b/click "#create-org-button")
    (is (b/check-for-error-message (str "An organization with the name '" org-name-1
                                        "' already exists. Please try using another name.")))
    ;; blank orgs can't be created
    (b/backspace-clear (count org-name-1) "#create-org-input")
    (b/click "#create-org-button")
    (is (b/check-for-error-message "Organization names can't be blank")))
  :cleanup (doseq [{:keys [user-id]} [user1 test-user]]
             (b/cleanup-test-user! :user-id user-id :groups true)))

(deftest-browser user-project-plan
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [{:keys [user-id email]} test-user
   user-project (str "Baz Qux " (random-id))
   user-cc {:cardnumber bstripe/valid-visa-cc}]
  (do
    ;; need to be a stripe customer
    (when-not (user-by-email email :stripe-id)
      (log/info (str "stripe customer created for " email))
      (user/create-user-stripe-customer! (user-by-email email)))
    (when-not (user-current-plan user-id)
      (stripe/create-subscription-user! (user-by-email email)))
    (b/is-soon (= plans-info/default-plan (:nickname (user-current-plan user-id))) 5000 250)
    (plans/wait-until-stripe-id email)
    (nav/log-in (:email test-user))
;;; user pay wall
    (nav/new-project user-project)
    (nav/go-project-route "/settings")
    (is (b/exists? disabled-set-private-button))
    (b/click plans/upgrade-link)
    (b/is-current-path "/user/plans")
    ;; get a plan for user
    (bstripe/enter-cc-information user-cc)
    (plans/click-use-card)
    (plans/click-upgrade-plan)
    (b/click set-private-button)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
    (log/info "set project to private access")
    ;; user downgrades to basic plan
    (b/click "#user-name-link")
    (b/click "#user-billing")
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (b/exists? ".button.nav-plans.subscribe")
    (log/info "downgraded user plan")
    ;; paywall is in place for their project they set private
    (b/click "#user-projects")
    (b/click (xpath "//a[contains(text(),'" user-project "')]"))
    ;; this is a user project, should redirect to /user/plans
    (is (b/exists? (xpath "//a[contains(@href,'/user/plans')]")))
    ;; set the project publicly viewable
    (b/click ".button.set-publicly-viewable")
    (b/click ".confirm-cancel-form-confirm")
    (log/info "set project to public access")
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
    ;; renew subscription to Unlimited
    (b/click "#user-name-link")
    (b/click "#user-billing")
    (b/click ".button.nav-plans.subscribe")
    (plans/click-upgrade-plan)
    (b/exists? ".button.nav-plans.unsubscribe")
    (log/info "upgraded user plan")
    ;; go back to projects
    (b/click "#user-projects")
    (b/click (xpath "//a[contains(text(),'" user-project "')]"))
    ;; set the project private
    (nav/go-project-route "/settings")
    (b/wait-until-displayed set-private-button)
    (b/click set-private-button)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
    (log/info "set project to private access")
    ;; downgrade to basic plan again
    (b/click "#user-name-link")
    (b/click "#user-billing")
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (b/exists? ".button.nav-plans.subscribe")
    ;; go to user project again
    (b/click "#user-projects")
    (b/click (xpath "//a[contains(text(),'" user-project "')]"))
    ;; paywall is in place
    (is (b/exists? (xpath "//a[contains(@href,'/user/plans')]")))
    ;; upgrade plans
    (b/click (xpath "//a[contains(@href,'/user/plans')]"))
    (plans/click-upgrade-plan)
    ;; paywall has been lifted
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]"))))
  :cleanup (b/cleanup-test-user! :email email :groups true))

(deftest-browser org-project-plan
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [{:keys [user-id email]} test-user
   org-name-1 (str "Foo Bar Inc. " (random-id))
   org-name-1-project (str "Foo Bar Article Reviews " (random-id))
   org-cc {:cardnumber bstripe/valid-visa-cc}]
  (do
    ;; need to be a stripe customer
    (when-not (user-by-email email :stripe-id)
      (log/info (str "stripe customer created for " email))
      (user/create-user-stripe-customer! (user-by-email email)))
    (when-not (user-current-plan user-id)
      (stripe/create-subscription-user! (user-by-email email)))
    ;; current plan
    (b/is-soon (= plans-info/default-plan (:nickname (user-current-plan user-id))) 5000 250)
    (plans/wait-until-stripe-id email)
    ;; start tests
    (nav/log-in (:email test-user))
    (create-org org-name-1)
    ;; create org project
    (b/click "#org-projects")
    (create-project-org org-name-1-project)
    (nav/go-project-route "/user/plans")
    (b/wait-until-loading-completes :pre-wait 100)
    (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
    (plans/click-use-card)
    (plans/click-upgrade-plan)
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (nav/go-project-route "/settings")
    (is (b/exists? disabled-set-private-button))
    ;; should be back at project settings
    (b/click set-private-button :delay 100)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
;;; org paywall
    ;; go to org, subscribe to basic
    (b/click "#user-name-link")
    (b/click "#user-billing")
    (b/click ".button.nav-plans.unsubscribe" :displayed? true)
    (b/click ".button.unsubscribe-plan")
    (is (b/exists? ".button.nav-plans.subscribe"))
    (switch-to-org org-name-1)
    ;; go to org projects
    (b/click "#org-projects")
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    ;; set the project publicly viewable
    (b/click ".button.set-publicly-viewable")
    (b/click ".confirm-cancel-form-confirm")
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
    ;; renew subscription to unlimited
    (switch-to-org org-name-1 :silent true)
    (nav/go-project-route "/user/plans")
    (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
    (plans/click-use-card)
    (plans/click-upgrade-plan)
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (is (b/exists? ".button.nav-plans.unsubscribe"))
    ;; set project to private again
    (switch-to-org org-name-1 :silent true)
    (b/click "#org-projects")
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    (nav/go-project-route "/settings")
    (b/click set-private-button)
    (b/click save-options-button)
    ;; downgrade to basic plan again
    (b/click "#user-name-link")
    (b/click "#user-billing")
    (b/click ".button.nav-plans.unsubscribe" :displayed? true)
    (b/click ".button.unsubscribe-plan")
    (log/info "downgraded org plan")
    (b/exists? ".button.nav-plans.subscribe"))
  :cleanup (b/cleanup-test-user! :email email :groups true))

(defn user-groups [email]
  (-> (user-by-email email :user-id)
      (group/read-groups)))


