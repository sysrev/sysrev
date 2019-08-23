(ns sysrev.test.browser.project-support
  (:require [clj-stripe.customers :as customers]
            [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [sysrev.api :as api]
            [sysrev.config.core :refer [env]]
            [sysrev.payment.plans :as plans]
            [sysrev.db.users :as users :refer [user-by-email]]
            [sysrev.project.core :as project]
            [sysrev.payment.stripe :as stripe]
            [sysrev.test.core :as test :refer [wait-until]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.plans :as test-plans]
            [sysrev.test.browser.stripe :as bstripe]))

;; if a user is created in the db, they won't have a stripe customer associated with them
;; to add them: (users/create-sysrev-stripe-customer! (user-by-email "browser+test@insilica.co"))
(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def support-submit-button {:xpath "//button[contains(text(),'Continue')]"})
(def cancel-support-button {:xpath "//button[contains(text(),'Cancel Support')]"})
(def stop-support-button {:xpath "//button[contains(text(),'Stop Support')]"})
;;(def support-button {:xpath "//a[contains(@class,'item')]/span[contains(text(),'Support')]"})

(defn support-message-xpath
  [string]
  {:xpath (str "//h3[contains(@class,'support-message') and contains(text(),\""
               string
               "\")]")})

(defn now-supporting-at-string
  [amount]
  (str "You are currently supporting this project at " amount " per month"))

(defn unsubscribe-user-from-all-support-plans
  [user]
  (let [user-subscriptions (plans/user-support-subscriptions user)
        subscriptions (map :id user-subscriptions)]
    (when-not (empty? subscriptions)
      (doall
       (map #(stripe/cancel-subscription! %) subscriptions)))))

;; if you need need to unsubscribe all plans between tests:
;; (unsubscribe-user-from-all-support-plans (user-by-email (:email b/test-login)))

;; This is testing a feature that is not used anymore
#_
(deftest-browser register-and-support-projects
  (and (test/db-connected?) (not (test/remote-test?)))
  [{:keys [email password]} b/test-login

   project-name "Sysrev Support Project Test"
   project-id (atom nil)]
  (do (log/info "register-and-support-projects")
      ;; cancel any previouly created subscriptions
      (unsubscribe-user-from-all-support-plans (user-by-email email))
      ;; delete any test user that currently exists
      (b/delete-test-user :email email)
      ;; register manually to create stripe account
      (nav/register-user email password)
      ;; create the new project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (assert stripe/stripe-secret-key)
      (assert stripe/stripe-public-key)
      ;; after registering, does the stripe customer exist?
      (is (= email
             (:email (stripe/execute-action
                      (customers/get-customer
                       (:stripe-id (user-by-email email)))))))
      ;; does stripe think the customer is registered to a basic plan?
      (is (= api/default-plan
             (-> (stripe/execute-action
                  (customers/get-customer
                   (:stripe-id (user-by-email email))))
                 :subscriptions :data first :items :data first :plan :name)))
;;; go to root project and support it (default is $5)
      (nav/go-project-route "/support")
      ;; this shouldn't work as there is no payment method
      (b/click support-submit-button)
      (is (b/exists? (test-plans/error-msg-xpath test-plans/no-payment-method)))
;;; update with a valid cc number and see if we can support a project
      (b/click ".button.update-payment")
      (bstripe/enter-cc-information {:cardnumber bstripe/valid-visa-cc
                                     :exp-date "0120"
                                     :cvc "123"
                                     :postal "11111"})
      (test-plans/click-use-card)
      ;; support the project at $10 per month
      (b/click {:xpath "//label[contains(text(),'$10')]/parent::div"})
      (b/click support-submit-button)
      ;; check that the confirmation message exists
      (is (b/exists? (support-message-xpath (now-supporting-at-string "$10.00"))))
      ;; change support to $50 month
      (b/click {:xpath "//label[contains(text(),'$50')]/parent::div"})
      (b/click support-submit-button)
      (is (b/exists? (support-message-xpath (now-supporting-at-string "$50.00"))))
      ;; is this all the user is paying for?
      (let [user-subscriptions (plans/user-support-subscriptions (user-by-email email))]
        (is (= 1
               (count user-subscriptions)))
        (is (= 5000
               (-> user-subscriptions
                   first
                   :quantity))))
      ;; subscribe at a custom amount of $200
      (b/click {:xpath "//input[@type='text']"})
      (b/set-input-text-per-char {:xpath "//input[@type='text']"} "200")
      (b/click support-submit-button)
      (is (b/exists? (support-message-xpath (now-supporting-at-string "$200.00"))))
      ;; is this all the user is paying for?
      (let [user-subscriptions (plans/user-support-subscriptions (user-by-email email))]
        (is (= 1
               (count user-subscriptions)))
        (is (= 20000
               (-> user-subscriptions
                   first
                   :quantity))))
      ;; is there a minimum support level?
      (b/click {:xpath "//input[@type='text']"})
      (b/set-input-text-per-char {:xpath "//input[@type='text']"} "0.99")
      (b/click support-submit-button)
      (is (b/exists? (test-plans/error-msg-xpath "Minimum support level is $1.00 per month")))
      ;; cancel support
      (b/click cancel-support-button)
      (b/click stop-support-button)
      (is (b/exists? {:xpath "//h1[text()='Support This Project']"}))
      ;;(unsubscribe-user-from-all-support-plans (user-by-email email))
      (is (empty? (plans/user-support-subscriptions (user-by-email email)))))

  :cleanup
  (do (when @project-id (project/delete-project @project-id))
      (b/delete-test-user)))
