(ns sysrev.test.browser.new-project
  (:require [clojure.test :refer [is use-fixtures]]
            [sysrev.payment.stripe :as stripe]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.orgs :as orgs]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.stripe :as bstripe]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.util :as util]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser user-create-new
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (user-create-new)"]
  (do (user/create-user-stripe-customer! (user-by-email (:email test-user)))
      (stripe/create-subscription-user! (user-by-email (:email test-user)))
      (plans/wait-until-stripe-id (:email test-user))
      (plans/wait-until-plan (:email test-user) plans-info/default-plan)
      (nav/log-in (:email test-user))
      (b/click "#new-project.button")
      ;; private is disabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and contains(@class,'disabled')]"))
      ;; signup through 'Pro Accounts' button
      (b/click (xpath "//a[contains(text(),'Pro Accounts')]"))
      (b/click plans/choose-pro-button)
      ;; update payment method
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc})
      (plans/click-use-card)
      (plans/click-upgrade-plan)
        ;;;;;;;;; cut here
      (is (= plans-info/unlimited-user (plans/user-db-plan (:email test-user))))
      ;; now try to create private project
      (nav/go-route "/")
      (b/click "#new-project.button")
      ;; private is enabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      ;; create the private project
      (b/set-input-text "#create-project .project-name input" project-name)
      (b/click (xpath "//p[contains(text(),'Private')]"
                      "/ancestor::div[contains(@class,'row')]"
                      "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      (b/click (xpath "//button[contains(text(),'Create Project')]") :displayed? true :delay 200 :timeout 15000)
      ;; is this project private?
      (b/exists? "i.grey.lock")
      (b/exists? (xpath "//span[contains(text(),'Private')]"))))

(deftest-browser group-create-new
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-create-new)"
   org-name (str "Bravo" (util/random-id))]
  (do (user/create-user-stripe-customer! (user-by-email (:email test-user)))
      (stripe/create-subscription-user! (user-by-email (:email test-user)))
      (plans/wait-until-stripe-id (:email test-user))
      (plans/wait-until-plan (:email test-user) plans-info/default-plan)
      (nav/log-in (:email test-user))
      (orgs/create-org org-name)
      (b/click "#new-project.button")
      ;; private is disabled
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and contains(@class,'disabled')]"))
      ;; create the private project
      (b/set-input-text "#create-project .project-name input" project-name)
      (b/exists? (xpath "//p[contains(text(),'Private')]"
                        "/ancestor::div[contains(@class,'row')]"
                        "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
      (b/click (xpath "//button[contains(text(),'Create Project')]") :displayed? true)
      ;; is this project private?
      (b/exists? "i.grey.lock")
      (b/exists? (xpath "//span[contains(text(),'Private')]")))
  :cleanup (b/cleanup-test-user! :email (:email test-user) :groups true))
