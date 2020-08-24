(ns sysrev.test.browser.new
  (:require [clojure.test :refer [is use-fixtures]]
            [sysrev.payment.plans :refer [group-current-plan]]
            [sysrev.payment.stripe :as stripe]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.orgs :as orgs]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.util :as util :refer [index-by random-id]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser user-create-new
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (user-create-new)"]
  (do (user/create-user-stripe-customer! (user-by-email (:email test-user)))
      (stripe/create-subscription-user! (user-by-email (:email test-user)))
      (plans/wait-until-stripe-id (:email test-user))
      (plans/wait-until-plan (:email test-user) stripe/default-plan)
      (nav/log-in (:email test-user))
      (b/click "button#new-project")
      ;; private is disabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and contains(@class,'disabled')]"))
      ;; signup through 'Pro Accounts' button
      (b/click (xpath "//a[contains(text(),'Pro Accounts')]"))
      (b/wait-until-displayed plans/choose-pro-button)
      (b/click plans/choose-pro-button)
      ;; update payment method
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
      (plans/click-use-card :delay 50)
      (plans/click-upgrade-plan)
        ;;;;;;;;; cut here
      (is (= "Unlimited_User" (plans/user-db-plan (:email test-user))))
      ;; now try to create private project
      (nav/go-route "/")
      (b/click "button#new-project")
      ;; private is enabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      ;; create the private project
      (b/set-input-text "form#create-project-form div.project-name input" project-name)
      (b/click (xpath "//p[contains(text(),'Private')]"
                      "/ancestor::div[contains(@class,'row')]"
                      "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      (b/click (xpath "//button[contains(text(),'Create Project')]"))
      ;; is this project private?
      (b/exists? (xpath "//i[contains(@class,'lock') and contains(@class,'grey')]"))
      (b/exists? (xpath "//span[contains(text(),'Private')]"))
      (b/cleanup-test-user! :email (:email test-user)))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))

(deftest-browser group-create-new
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-create-new)"
   org-name (str "Bravo" (util/random-id))]
  (do (user/create-user-stripe-customer! (user-by-email (:email test-user)))
      (stripe/create-subscription-user! (user-by-email (:email test-user)))
      (plans/wait-until-stripe-id (:email test-user))
      (plans/wait-until-plan (:email test-user) stripe/default-plan)
      (nav/log-in (:email test-user))
      (orgs/create-org org-name)
      (b/click "button#new-project")
      ;; private is disabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and contains(@class,'disabled')]"))
      ;; signup through 'Pro Accounts' button
      (b/click (xpath "//a[contains(text(),'Pro Accounts')]"))
      (b/wait-until-displayed plans/choose-pro-button)
      (b/click orgs/choose-team-pro-button)
      ;; where going to use an existing plan
      (b/click (xpath "//a[contains(text(),'" org-name "')]"))
      ;; update payment method
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
      (plans/click-use-card :delay 50)
      (plans/click-upgrade-plan)
      ;;;;;;; cut here
      (is (= "Unlimited_Org" (-> (orgs/user-groups (:email test-user))
                                 first
                                 :group-id
                                 group-current-plan
                                 :nickname)))
      ;; now try to create private project
      (b/click "a#org-projects")
      (b/click "button#new-project")
      ;; private is enabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      ;; create the private project
      (b/set-input-text "form#create-project-form div.project-name input" project-name)
      (b/click (xpath "//p[contains(text(),'Private')]"
                      "/ancestor::div[contains(@class,'row')]"
                      "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      (b/click (xpath "//button[contains(text(),'Create Project')]"))
      ;; is this project private?
      (b/exists? (xpath "//i[contains(@class,'lock') and contains(@class,'grey')]"))
      (b/exists? (xpath "//span[contains(text(),'Private')]"))
      (b/cleanup-test-user! :email (:email test-user)))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))
