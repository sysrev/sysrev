(ns sysrev.test.browser.orgs
  (:require [clojure.string :as str]
            [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.group.core :as group]
            [sysrev.payment.stripe :as stripe]
            [sysrev.payment.plans :refer [user-current-plan group-current-plan]]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.user-profiles :as user-profiles]
            [sysrev.test.browser.xpath :refer [xpath]]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.core :as test]
            [sysrev.util :as util :refer [index-by random-id]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;; org tabs
(def org-projects "#org-projects")
(def org-users "#org-members")
(def org-billing "#org-billing")
(def user-orgs "#user-orgs")

(def create-org-input "#create-org-input")
(def create-org-button "#create-org-button")
(def org-user-table "#org-user-table")

;; pricing workflow elements
(def choose-team-pro-button (xpath "//button[contains(text(),'Choose Team Pro')]"))
(def create-account-h4 (xpath "//h4[contains(text(),'Create a free account before moving on to team creation')]"))
(def create-team-h4 (xpath "//h4[contains(text(),'create a Sysrev organization for your team')]"))

(defn change-user-permission-dropdown [username]
  (let [[username] (str/split username #"@")]
    (xpath "//table[@id='org-user-table']/tbody/tr/td/a[text()='" username "']"
           "/ancestor::tr"
           "/td/div[contains(@class,'change-org-user')]")))
(def change-role (xpath "//span[contains(text(),'Change role...')]"))
(def org-change-role-button "#org-change-role-button")
(def change-org-dropdown "#change-org-dropdown")
(def current-org-dropdown (xpath "//div[@id='change-org-dropdown']/div[@class='text']"))
(def disabled-set-private-button (xpath "//button[@id='public-access_private' and contains(@class,'disabled')]"))
(def active-set-private-button (xpath "//button[@id='public-access_private' and contains(@class,'active')]"))
(def set-private-button "#public-access_private")
(def save-options-button "#save-options")
(def no-payment-on-file (xpath "//div[contains(text(),'No payment method on file')]"))

(defn payment-method [{:keys [exp-date last-4]}]
  (xpath "//div[contains(text(),'Visa expiring on " exp-date " and ending in " last-4 "')]"))

(defn user-group-permission
  [user-id group-name]
  (group/user-group-permission user-id (group/group-name->id group-name)))

(defn org-user-table-entries []
  ;; go to the user table
  (b/click "a#org-members")
  (b/wait-until-exists org-user-table)
  (->> (taxi/find-elements-under org-user-table {:tag :tr})
       (mapv taxi/text)
       (mapv #(str/split % #"\n"))
       (mapv #(hash-map :name (first %) :permission (second %)))
       (index-by :name)))

(defn add-user-to-org
  "Must be in Organization Settings of the project to add user to"
  [username]
  (let [[username] (str/split username #"@")]
    (b/click org-users)
    (b/click "#add-member-button" :delay 400)
    (b/set-input-text-per-char "#org-search-users-input" username)
    (b/click (xpath "//button[@id='submit-add-member' and not(contains(@class,'disabled'))]") :delay 400)))

(defn change-user-permission
  "Set username to permission. Must be in Organization Settings of the
  org you wish to change permissions in. permission is either 'Owner'
  or 'Member'."
  [username permission]
  (let [[username] (str/split username #"@")]
    (b/click (change-user-permission-dropdown username) :delay 200)
    (b/click change-role :delay 400)
    (b/click (xpath "//label[contains(text(),'" permission "')]"
                    "/ancestor::h4" "//label")
             :delay 300)
    (b/click org-change-role-button :delay 300)
    (log/infof "changed org user permission (%s => %s)"
               (pr-str username) (pr-str permission))))

(defn create-org [org-name]
  (b/click user-profiles/user-name-link)
  (b/click user-orgs)
  (b/set-input-text-per-char create-org-input org-name)
  (b/click create-org-button)
  (b/wait-until-exists "button#new-project")
  (b/wait-until-loading-completes :pre-wait 100)
  (log/infof "created org %s" (pr-str org-name)))

(defn create-project-org
  "Must be in the Organization Settings for the org for which the project is being created"
  [project-name]
  (b/click "button#new-project")
  (b/set-input-text "form#create-project-form div.project-name input" project-name)
  (b/click (xpath "//button[contains(text(),'Create Project')]"))
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath "//div[contains(@class,'project-title')]"
          "//a[contains(text(),'" project-name "')]"))
  (b/wait-until-loading-completes :pre-wait true))

(defn switch-to-org
  "switch to org-name, must be in Organization Settings"
  [org-name & {:keys [silent]}]
  (b/click user-profiles/user-name-link)
  (b/click "#user-orgs")
  (b/click (xpath "//a[text()='" org-name "']"))
  (b/wait-until-exists org-users)
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
   [user1-name] (str/split (:email user1) #"@")
   user-role #(get-in (org-user-table-entries) [% :permission])]
  (do
    (nav/log-in (:email test-user))
    ;; a person can create a org and they are automatically made owners
    (create-org org-name-1)
    (is (some #{"owner"} (user-group-permission user-id org-name-1)))
;;; an owner can add a user to the org
    ;; add a user
    (add-user-to-org (:email user1))
    (b/is-soon (= "member" (user-role user1-name)) 3000 50)
    ;;an owner can change permissions of a member
    (change-user-permission (:email user1) "Owner")
    (b/is-soon (= "owner" (user-role user1-name)) 3000 50)
    ;; only an owner can change permissions, not a member
    (change-user-permission (:email user1) "Member")
    (nav/log-in (:email user1))
    (b/click user-profiles/user-name-link)
    (b/click user-orgs)
    (b/click (xpath "//a[text()='" org-name-1 "']"))
    (b/is-soon (not (taxi/exists? (change-user-permission-dropdown (:email test-user)))))
    ;; an org is switched, the correct user list shows up
    (nav/log-in (:email test-user))
    (b/click user-profiles/user-name-link)
    ;; create a new org
    (create-org org-name-2)
    ;; should only be one user in this org
    (b/is-soon (= 1 (count (org-user-table-entries))) 3000 50)
    ;; switch back to the other org, there is two users in this one
    (switch-to-org org-name-1)
    (b/is-soon (= 2 (count (org-user-table-entries))) 3000 50)
    ;; only an owner or admin of an org can create projects for that org
    (b/click org-projects :delay 30)
    (create-project-org org-name-1-project)
    ;; add user1 to Baz Qux as an owner
    (nav/go-route "/org/users")
    (switch-to-org org-name-2)
    (add-user-to-org (:email user1))
    (change-user-permission (:email user1) "Owner")
    ;; log-in as user1 and see that they cannot create group projects
    (nav/log-in (:email user1))
    (b/click user-profiles/user-name-link)
    (b/click user-orgs)
    (b/click (xpath "//a[text()='" org-name-1 "']") :delay 20)
    ;; org-users and projects links exists, but billing link doesn't exist
    (b/is-soon (and (taxi/exists? org-users) (taxi/exists? org-projects)))
    (b/is-soon (not (taxi/exists? org-billing)))
    ;; group projects exists, but not the create project input
    (b/click org-projects :delay 30)
    (b/exists? "#projects")
    (b/is-soon (not (taxi/exists? "form.create-project")))
    ;; user can't change permissions
    (b/is-soon (not (taxi/exists? (change-user-permission-dropdown (:email test-user)))))
    ;; switch to org-name-2
    (switch-to-org org-name-2)
    ;; user can create projects here
    (b/click org-projects :delay 30)
    (b/wait-until-exists "button#new-project")
    ;; can change user permissions for browser+test
    (b/click org-users)
    (b/wait-until-exists (change-user-permission-dropdown (:email test-user)))
    ;; billing link is available
    (b/exists? org-billing)
    ;; duplicate orgs can't be created
    (nav/log-in (:email test-user))
    (b/click user-profiles/user-name-link)
    (b/click user-orgs)
    (b/set-input-text-per-char create-org-input org-name-1)
    (b/click create-org-button)
    (is (b/check-for-error-message (str "An organization with the name '" org-name-1
                                        "' already exists. Please try using another name.")))
    ;; blank orgs can't be created
    (b/backspace-clear (count org-name-1) create-org-input)
    (b/click create-org-button)
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
    (b/is-soon (= stripe/default-plan (:name (user-current-plan user-id))) 5000 250)
    (plans/wait-until-stripe-id email)
    (nav/log-in (:email test-user))
;;; user pay wall
    (nav/new-project user-project)
    (nav/go-project-route "/settings")
    (is (b/exists? disabled-set-private-button))
    (b/click plans/upgrade-link)
    (b/is-current-path "/user/plans")
    (is (b/exists? no-payment-on-file))
    ;; get a plan for user
    (b/click "a.payment-method.add-method")
    (b/is-current-path "/user/payment")
    (bstripe/enter-cc-information user-cc)
    (plans/click-use-card :delay 50)
    (plans/click-upgrade-plan)
    (b/click set-private-button)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
    (log/info "set project to private access")
    ;; user downgrades to basic plan
    (b/click user-profiles/user-name-link)
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
    (b/click ".ui.button.set-publicly-viewable")
    (b/click "#confirm-cancel-form-confirm")
    (log/info "set project to public access")
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
    ;; renew subscription to Unlimited
    (b/click user-profiles/user-name-link)
    (b/click "#user-billing")
    (b/click ".button.nav-plans.subscribe" :delay 50)
    (plans/click-upgrade-plan :delay 50)
    (b/exists? ".button.nav-plans.unsubscribe")
    (log/info "upgraded user plan")
    ;; go back to projects
    (b/click "#user-projects")
    (b/click (xpath "//a[contains(text(),'" user-project "')]"))
    ;; set the project private
    (nav/go-project-route "/settings" :wait-ms 30)
    (b/wait-until-displayed set-private-button)
    (b/click set-private-button :delay 30)
    (b/click save-options-button :delay 30)
    (is (b/exists? active-set-private-button))
    (log/info "set project to private access")
    ;; downgrade to basic plan again
    (b/click user-profiles/user-name-link)
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
    (b/is-soon (= stripe/default-plan (:name (user-current-plan user-id))) 5000 250)
    (plans/wait-until-stripe-id email)
    ;; start tests
    (nav/log-in (:email test-user))
    (create-org org-name-1)
    ;; create org project
    (b/click org-projects :delay 30)
    (create-project-org org-name-1-project)
    (nav/go-project-route "/settings")
    (is (b/exists? disabled-set-private-button))
    (b/click plans/upgrade-link)
    ;; subscribe to plans
    (log/info "attempting plan subscription")
    (b/click "a.payment-method.add-method")
    ;; enter payment information
    (bstripe/enter-cc-information org-cc)
    (plans/click-use-card :delay 50)
    (plans/click-upgrade-plan)
    ;; should be back at project settings
    (b/click set-private-button :delay 100)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
;;; org paywall
    ;; go to org, subscribe to basic
    (switch-to-org org-name-1)
    (b/click org-billing :delay 30)
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (is (b/exists? ".button.nav-plans.subscribe"))
    ;; go to org projects
    (b/click org-projects :delay 30)
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    ;; should redirect to /org/<org-id>/plans
    (is (b/exists? (xpath "//a[contains(@href,'/org') and contains(@href,'/plans')]")))
    ;; set the project publicly viewable
    (b/click ".ui.button.set-publicly-viewable")
    (b/click "#confirm-cancel-form-confirm")
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
    ;; renew subscription to unlimited
    (switch-to-org org-name-1 :silent true)
    (b/click org-billing :delay 30)
    (b/click ".button.nav-plans.subscribe" :delay 30)
    (plans/click-upgrade-plan)
    (is (b/exists? ".button.nav-plans.unsubscribe"))
    ;; set project to private again
    (switch-to-org org-name-1 :silent true)
    (b/click org-projects :delay 30)
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    (nav/go-project-route "/settings" :pre-wait-ms 50 :wait-ms 50)
    (b/click set-private-button)
    (b/click save-options-button)
    ;; downgrade to basic plan again
    (switch-to-org org-name-1 :silent true)
    (b/click org-billing :delay 30)
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (log/info "downgraded org plan")
    (b/exists? ".button.nav-plans.subscribe")
    ;; go to project again
    (b/click org-projects :delay 30)
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    ;; paywall is in place
    (b/exists? (xpath "//a[contains(@href,'/org') and contains(@href,'/plans')]"))
    (b/click (xpath "//a[contains(text(),'Upgrade your plan')]") :delay 50)
    (log/info "got paywall on org project")
    (plans/click-upgrade-plan)
    ;; paywall has been lifted
    (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
  :cleanup (b/cleanup-test-user! :email email :groups true))

(defn user-groups [email]
  (-> (user-by-email email :user-id)
      (group/read-groups)))

;; test no-account pricing org sign up
(deftest-browser subscribe-to-org-unlimited-through-pricing-no-account
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [org-name (str "Foo Bar Inc. " (random-id))
   email (format "foo+%s@bar.com" (random-id))]
  (do
    (nav/go-route "/")
    (taxi/execute-script "window.scrollTo(0,document.body.scrollHeight);")
    (b/wait-until-displayed choose-team-pro-button)
    (b/click choose-team-pro-button)
    ;; register
    (b/wait-until-displayed create-account-h4)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" b/test-password)
    (b/click "button[name='submit']")
    ;; create a team
    (b/wait-until-displayed create-team-h4)
    (b/set-input-text-per-char create-org-input org-name)
    (b/click create-org-button)
    ;; upgrade plan
    (b/wait-until-displayed plans/upgrade-plan-h2 10000 100)
    (is (= "Basic" (-> (user-groups email)
                       first
                       :group-id
                       group-current-plan
                       :name)))
    ;; refresh to make sure state isn't an issue
    (taxi/refresh)
    (b/wait-until-displayed plans/upgrade-plan-h2)
    ;; update payment method
    (b/click "a.payment-method.add-method" :delay 50)
    ;; refresh to test redirect uri
    (taxi/refresh)
    (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
    (plans/click-use-card :delay 50)
    (plans/click-upgrade-plan)
    ;; ;; we have an unlimited plan
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (is (= "Unlimited_Org" (-> (user-groups email)
                               first
                               :group-id
                               group-current-plan
                               :name))))
  :cleanup (b/cleanup-test-user! :email email :groups true))

;; test that pricing works from any point in the workflow
(deftest-browser org-pricing-flow-intermittent
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [email (format "baz+%s@qux.com" (random-id))
   org-name (str "Foo Bar Inc. " (random-id))]
  (do
    (nav/go-route "/")
    (taxi/execute-script "window.scrollTo(0,document.body.scrollHeight);")
    (b/wait-until-displayed choose-team-pro-button)
    (b/click choose-team-pro-button)
    (b/wait-until-displayed create-account-h4)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" b/test-password)
    (b/click "button[name='submit']")
    ;; don't create a team
    (b/wait-until-displayed create-team-h4)
    (nav/go-route "/")
    ;; let's go through pricing again for a team pro account
    (b/wait-until-displayed plans/pricing-link)
    (b/click plans/pricing-link)
    (b/wait-until-displayed choose-team-pro-button)
    (b/click choose-team-pro-button)
    ;; create a team
    (b/wait-until-displayed create-team-h4)
    (b/set-input-text-per-char create-org-input org-name)
    (b/click create-org-button)
    (b/wait-until-displayed plans/upgrade-plan-h2)
    (b/wait-until-loading-completes :pre-wait 100)
    ;; whoops nevermind, got cold feet
    (nav/go-route "/")
    ;; let's try to pay again
    (b/click plans/pricing-link)
    (b/wait-until-displayed choose-team-pro-button)
    (b/click choose-team-pro-button)
    ;; click the existing team
    (taxi/refresh)
    (b/wait-until-displayed (xpath "//a[text()='" org-name"']") 5000 100)
    (b/click (xpath "//a[text()='" org-name"']"))
    ;; upgrade plan
    (b/wait-until-displayed plans/upgrade-plan-h2)
    (is (= "Basic" (-> (user-groups email)
                       first
                       :group-id
                       group-current-plan
                       :name)))
    ;; refresh to make sure state isn't an issue
    (taxi/refresh)
    (b/wait-until-displayed plans/upgrade-plan-h2)
    ;; update payment method
    (b/click "a.payment-method.add-method" :delay 50)
    ;; refresh to test redirect uri
    (taxi/refresh)
    (bstripe/enter-cc-information
     {:cardnumber bstripe/valid-visa-cc})
    (plans/click-use-card :delay 50)
    (plans/click-upgrade-plan)
    ;; ;; we have an unlimited plan
    (b/wait-until-displayed ".button.nav-plans.unsubscribe")
    (is (= "Unlimited_Org" (-> (user-groups email)
                               first
                               :group-id
                               group-current-plan
                               :name))))
  :cleanup (b/cleanup-test-user! :email email :groups true))
