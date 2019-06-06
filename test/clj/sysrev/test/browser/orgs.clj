(ns sysrev.test.browser.orgs
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.api :as api]
            [sysrev.db.groups :as groups]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.user-profiles :as user-profiles]
            [sysrev.test.browser.xpath :refer [xpath]]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.core :as test]
            [sysrev.stripe :as stripe]
            [sysrev.shared.util :refer [->map-with-key]]))

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
(defn change-user-permission-dropdown [username]
  (xpath "//table[@id='org-user-table']/tbody/tr/td/a[text()='" username "']"
         "/ancestor::tr"
         "/td/div[contains(@class,'dropdown')]"))
(def change-role (xpath "//span[contains(text(),'Change role...')]"))
(def member-permission-owner-radio (xpath "//label[contains(text(),'Owner')]" "/ancestor::h4" "//label"))
(def member-permission-member-radio (xpath "//label[contains(text(),'Member')]" "/ancestor::h4" "//label"))
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
  (groups/user-group-permission user-id (groups/group-name->group-id group-name)))

(defn org-user-table-entries []
  (b/wait-until-exists org-user-table)
  (->> (taxi/find-elements-under org-user-table {:tag :tr})
       (mapv taxi/text)
       (mapv #(str/split % #"\n"))
       (mapv #(hash-map :name (first %) :permission (second %)))
       (->map-with-key :name)))

(defn add-user-to-org
  "Must be in Organization Settings of the project to add user to"
  [username]
  (b/click org-users)
  (b/click "#add-member-button" :delay 200)
  (b/set-input-text-per-char "#org-search-users-input" username)
  (b/click "#submit-add-member" :delay 200))

(defn change-user-permission
  "Set username to permission. Must be in Organization Settings of the
  org you wish to change permissions in. permission is either 'Owner'
  or 'Member'."
  [username permission]
  (b/click (change-user-permission-dropdown username))
  (b/click change-role)
  (b/click (xpath "//label[contains(text(),'" permission "')]" "/ancestor::h4" "//label"))
  (b/click org-change-role-button))

(defn create-org [org-name]
  (b/click user-profiles/user-name-link)
  (b/click user-orgs)
  (b/set-input-text-per-char create-org-input org-name)
  (b/click create-org-button)
  (b/wait-until-exists "#add-member-button"))

(defn create-project-org
  "Must be in the Organization Settings for the org for which the project is being created"
  [project-name]
  (b/wait-until-exists "form.create-project")
  (b/set-input-text "form.create-project div.project-name input" project-name)
  (b/click "form.create-project .button.create-project")
  (Thread/sleep 100)
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath (format "//span[contains(@class,'project-title') and text()='%s']" project-name)
          "//ancestor::div[@id='project']"))
  (b/wait-until-loading-completes :pre-wait 100))

(defn switch-to-org
  "switch to org-name, must be in Organization Settings"
  [org-name]
  (b/click user-profiles/user-name-link)
  (b/click "#user-orgs")
  (b/click (xpath "//a[text()='" org-name "']"))
  (b/wait-until-exists org-users))

(deftest-browser simple-org-tests
  (test/db-connected?)
  [org-name-1 "Foo Bar, Inc."
   org-name-2 "Baz Qux"
   org-name-1-project "Foo Bar Article Reviews"
   email (:email b/test-login)
   user-id (:user-id (users/get-user-by-email email))
   test-user {:name "foo", :email "foo@bar.com", :password "foobar"}
   user-role #(get-in (org-user-table-entries) [% :permission])]
  (do
    (nav/log-in)
    ;; a person can create a org and they are automatically made owners
    (create-org org-name-1)
    (is (some (set ["owner"]) (user-group-permission user-id org-name-1)))
;;; an owner can add a user to the org
    ;; add a user
    (b/create-test-user :name (:name test-user)
                        :email (:email test-user)
                        :password (:password test-user))
    (add-user-to-org (:name test-user))
    (b/is-soon (= "member" (user-role "foo")) 2000 100)
    ;;an owner can change permissions of a member
    (change-user-permission (:name test-user) "Owner")
    (b/is-soon (= "owner" (user-role "foo")) 2000 100)
    ;; only an owner can change permissions, not a member
    (change-user-permission (:name test-user) "Member")
    (nav/log-in (:email test-user) (:password test-user))
    (b/click user-profiles/user-name-link)
    (b/click user-orgs)
    (b/click (xpath "//a[text()='" org-name-1 "']"))
    (is (not (taxi/exists? (change-user-permission-dropdown "browser+test"))))
    ;; an org is switched, the correct user list shows up
    (nav/log-in)
    (b/click user-profiles/user-name-link)
    ;; create a new org
    (create-org org-name-2)
    ;; should only be one user in this org
    (is (= 1 (count (org-user-table-entries))))
    ;; switch back to the other org, there is two users in this one
    (switch-to-org org-name-1)
    (is (= 2 (count (org-user-table-entries))))
    ;; only an owner or admin of an org can create projects for that org
    (b/click org-projects)
    (create-project-org org-name-1-project)
    ;; add test-user to Baz Qux as an owner
    (nav/go-route "/org/users")
    (switch-to-org org-name-2)
    (add-user-to-org (:name test-user))
    (change-user-permission (:name test-user) "Owner")
    ;; log-in as test-user and see that they cannot create group projects
    (nav/log-in (:email test-user) (:password test-user))
    (b/click user-profiles/user-name-link)
    (b/click user-orgs)
    (b/click (xpath "//a[text()='" org-name-1 "']"))
    ;; org-users and projects links exists, but billing link doesn't exist
    (is (and (b/exists? org-users) (b/exists? org-projects)))
    (is (not (taxi/exists? org-billing)))
    ;; group projects exists, but not the create project input
    (b/click org-projects)
    (b/exists? "#public-projects")
    (is (not (taxi/exists? "form.create-project")))
    ;; user can't change permissions
    (is (not (taxi/exists? (change-user-permission-dropdown "browser+test"))))
    ;; switch to org-name-2
    (switch-to-org org-name-2)
    ;; user can create projects here
    (b/click org-projects)
    (b/wait-until-exists "form.create-project")
    (is (b/exists? "form.create-project"))
    ;; can change user permissions for browser+test
    (b/click org-users)
    (b/wait-until-exists (change-user-permission-dropdown "browser+test"))
    ;; billing link is available
    (is (b/exists? org-billing)))
  :cleanup
  (doseq [{:keys [email]} [b/test-login test-user]]
             (b/cleanup-test-user! :email email :groups true)))

;; for manual testing:
;; delete a customer's card:
#_(let [stripe-id (-> email users/get-user-by-email :stripe-id)
        source-id (-> (stripe/read-default-customer-source stripe-id) :id)]
    source-id
    ;;(stripe/delete-customer-card! stripe-id source-id)
    )

;; delete a org's card:
#_(let [group-id (-> (groups/read-groups user-id) first :id)
        stripe-id (groups/get-stripe-id group-id)
        source-id (-> (stripe/read-default-customer-source stripe-id) :id)]
    (stripe/delete-customer-card! stripe-id source-id))

(deftest-browser org-plans
  (and (test/db-connected?) (not (test/remote-test?)))
  [org-name-1 "Foo Bar, Inc."
   org-name-1-project "Foo Bar Article Reviews"
   user-project "Baz Qux"
   org-cc {:cardnumber bstripe/valid-visa-cc
           :exp-date "0125"
           :cvc "123"
           :postal "12345"}
   user-cc {:cardnumber bstripe/valid-visa-cc
            :exp-date "0122"
            :cvc "123"
            :postal "11111"}
   email (:email b/test-login)
   user-id (:user-id (users/get-user-by-email email))]
  (do
    (alter-var-root #'sysrev.api/paywall-grandfather-date (fn [paywall-grandfather-date]
                                                            (constantly "2019-01-01 00:00:00")))
    ;; need to be a stripe customer
    (when-not (:stripe-id (users/get-user-by-email email))
      (log/info (str "Stripe Customer created for " email))
      (users/create-sysrev-stripe-customer! (users/get-user-by-email email)))
    (when-not (-> email users/get-user-by-email :user-id (api/current-plan) (get-in [:result :plan]))
      (stripe/create-subscription-user! (users/get-user-by-email email)))
    ;; current plan
    (b/is-soon (= (get-in (api/current-plan user-id) [:result :plan :name])
                  stripe/default-plan)
               2000 200)
    (plans/wait-until-stripe-id email)
    ;; start tests
    (nav/log-in)
    (create-org org-name-1)
    ;; create org project
    (b/click org-projects)
    (create-project-org org-name-1-project)
    (nav/go-project-route "/settings")
    (is (b/exists? disabled-set-private-button))
    (b/click plans/upgrade-link)
    ;; subscribe to plans
    (log/info "attempting plan subscription")
    (b/click "a.payment-method.add-method")
    ;; enter payment information
    (bstripe/enter-cc-information org-cc)
    (b/click plans/use-card)
    (b/click ".button.upgrade-plan")
    ;; should be back at project settings
    (b/click set-private-button)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
;;; user pay wall
    ;;
    (log/info "Testing User Paywall")
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
    (b/click plans/use-card)
    (b/click ".button.upgrade-plan")
    (b/click set-private-button)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
    ;; user downgrades to basic plan
    (b/click user-profiles/user-name-link)
    (b/click "#user-billing")
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (b/exists? ".button.nav-plans.subscribe")
    ;; paywall is in place for their project they set private
    (b/click "#user-projects")
    (b/click (xpath "//a[contains(text(),'" user-project "')]"))
    ;; this is a user project, should redirect to /user/plans
    (is (b/exists? (xpath "//a[contains(@href,'/user/plans')]")))
    ;; set the project publicly viewable
    (b/click "#set-publicly-viewable")
    (b/click "#confirm-cancel-form-confirm")
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
    ;; renew subscription to Unlimited
    (b/click user-profiles/user-name-link)
    (b/click "#user-billing")
    (b/click ".button.nav-plans.subscribe")
    (b/click ".button.upgrade-plan")
    (is (b/exists? ".button.nav-plans.unsubscribe"))
    ;; go back to projects
    (b/click "#user-projects")
    (b/click (xpath "//a[contains(text(),'" user-project "')]"))
    ;; set the project private
    (nav/go-project-route "/settings")
    (b/click set-private-button)
    (b/click save-options-button)
    (is (b/exists? active-set-private-button))
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
    #_
    (when-not (try (b/exists? ".button.upgrade-plan")
                   (catch Throwable e false))
      (taxi/refresh)
      (log/info "Browser was refreshed"))
    (b/click ".button.upgrade-plan" :displayed? true)
    ;; paywall has been lifted
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
;;; org paywall
    ;;
    ;; go to org, subscribe to basic
    (log/info "Testing Org Paywall")
    (switch-to-org org-name-1)
    (b/click org-billing)
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (is (b/exists? ".button.nav-plans.subscribe"))
    ;; go to org projects
    (b/click org-projects)
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    ;; should redirect to /org/<org-id>/plans
    (is (b/exists? (xpath "//a[contains(@href,'/org') and contains(@href,'/plans')]")))
    ;; set the project publicly viewable
    (b/click "#set-publicly-viewable")
    (b/click "#confirm-cancel-form-confirm")
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]")))
    ;; renew subscription to unlimited
    (switch-to-org org-name-1)
    (b/click org-billing)
    (b/click ".button.nav-plans.subscribe")
    (b/click ".button.upgrade-plan")
    (is (b/exists? ".button.nav-plans.unsubscribe"))
    ;; set project to private again
    (switch-to-org org-name-1)
    (b/click org-projects)
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    (nav/go-project-route "/settings")
    (b/click set-private-button)
    (b/click save-options-button)
    ;; downgrade to basic plan again
    (switch-to-org org-name-1)
    (b/click org-billing)
    (b/click ".button.nav-plans.unsubscribe")
    (b/click ".button.unsubscribe-plan")
    (is (b/exists? ".button.nav-plans.subscribe"))
    ;; go to project again
    (b/click org-projects)
    (b/click (xpath "//a[contains(text(),'" org-name-1-project "')]"))
    ;; paywall is in place
    (is (b/exists? (xpath "//a[contains(@href,'/org') and contains(@href,'/plans')]")))
    (b/click (xpath "//a[contains(text(),'Upgrade your plan')]"))
    #_
    (when-not (try (b/exists? ".button.upgrade-plan")
                   (catch Throwable e false))
      (taxi/refresh)
      (log/info "Browser was refreshed"))
    (b/click ".button.upgrade-plan" :displayed? true)
    ;; paywall has been lifted
    (is (b/exists? (xpath "//span[contains(text(),'Label Definitions')]"))))
  :cleanup (do (some-> email (users/get-user-by-email) (users/delete-sysrev-stripe-customer!))
               (b/cleanup-test-user! :email email :groups true)))
