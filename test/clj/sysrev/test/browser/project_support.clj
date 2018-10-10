(ns sysrev.test.browser.project-support
  (:require [clj-stripe.customers :as customers]
            [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [sysrev.api :as api]
            [sysrev.config.core :refer [env]]
            [sysrev.db.plans :as plans]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]
            [sysrev.test.browser.core :as browser :refer [deftest-browser]]
            [sysrev.test.browser.create-project :as create-project]
            [sysrev.test.browser.plans :as test-plans]
            [sysrev.test.browser.navigate :as navigate :refer [log-in log-out]]
            [sysrev.test.core :refer [default-fixture wait-until full-tests?]]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

(def support-submit-button {:xpath "//button[contains(text(),'Continue')]"})
(def cancel-support-button {:xpath "//button[contains(text(),'Cancel Support')]"})
(def stop-support-button {:xpath "//button[contains(text(),'Stop Support')]"})
;;(def support-button {:xpath "//a[contains(@class,'item')]/span[contains(text(),'Support')]"})


(defn enter-cc-information
  [{:keys [cardnumber exp-date cvc postal]}]
  (let [;; note: stripe could change the frame names
        cardnumber-input-iframe {:xpath "//iframe[@name='__privateStripeFrame4']"}
        cardnumber-input "input[name~='cardnumber']"
        exp-date-iframe {:xpath "//iframe[@name='__privateStripeFrame5']"}
        exp-date-input "input[name~='exp-date']"
        cvc-iframe {:xpath "//iframe[@name='__privateStripeFrame6']"}
        cvc-input "input[name~='cvc']"
        postal-iframe {:xpath "//iframe[@name='__privateStripeFrame7']"}
        postal-input "input[name~='postal']"]
    ;; let's reset to be user we are in the default iframe
    (taxi/switch-to-default)
    (browser/wait-until-displayed {:xpath "//h1[text()='Enter your Payment Method']"})
    ;; switch the the proper iframe. note that the name could change if stripe updates their library
    (taxi/switch-to-frame cardnumber-input-iframe)
    ;; clear anything that could be in the form
    (taxi/clear cardnumber-input)
    (browser/set-input-text-per-char cardnumber-input cardnumber)
    ;; switch back to default
    (taxi/switch-to-default)
    ;; switch to month input iframe
    (taxi/switch-to-frame exp-date-iframe)
    (taxi/clear exp-date-input)
    (browser/set-input-text-per-char exp-date-input exp-date)
    ;; swtich back to default
    (taxi/switch-to-default)
    ;; switch to cvc iframe
    (taxi/switch-to-frame cvc-iframe)
    (taxi/clear cvc-input)
    (browser/set-input-text-per-char cvc-input cvc)
    ;; switch back to default frame
    (taxi/switch-to-default)
    ;; switch to post code frame
    (taxi/switch-to-frame postal-iframe)
    (taxi/clear postal-input)
    (browser/set-input-text-per-char postal-input postal)
    ;; where done, return back to default
    (taxi/switch-to-default)))

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
;; (unsubscribe-user-from-all-support-plans (users/get-user-by-email (:email browser/test-login)))

;; This is testing a feature that is not used anymore
(deftest-browser register-and-support-projects
  (when (browser/db-connected?)
    (log/info "register-and-support-projects")
    ;; TODO: fix text input in Stripe payment form
    (try
      (when (and (not= :remote-test (-> env :profile))
                 (browser/db-connected?))
        (let [{:keys [email password]} browser/test-login
              project-name "SysRev Support Project Test"]
          ;; cancel any previouly created subscriptions
          (unsubscribe-user-from-all-support-plans (users/get-user-by-email email))
          ;; delete any test user that currently exists
          (browser/delete-test-user :email email)
          ;; register manually to create stripe account
          (navigate/register-user email password)
          ;; create the new project
          (create-project/create-project project-name)
          (assert stripe/stripe-secret-key)
          (assert stripe/stripe-public-key)
          ;; after registering, does the stripe customer exist?
          (is (= email
                 (:email (stripe/execute-action
                          (customers/get-customer
                           (:stripe-id (users/get-user-by-email email)))))))
          ;; does stripe think the customer is registered to a basic plan?
          (is (= api/default-plan
                 (-> (stripe/execute-action
                      (customers/get-customer
                       (:stripe-id (users/get-user-by-email email))))
                     :subscriptions :data first :items :data first :plan :name)))
;;; go to root project and support it (default is $5)
          (browser/go-project-route "/support")
          (browser/wait-until-displayed support-submit-button)
          ;; this shouldn't work as there is no payment method
          (browser/click support-submit-button)
          (browser/wait-until-displayed test-plans/update-payment-button)
          (is (browser/exists? (test-plans/error-msg-xpath test-plans/no-payment-method)))
;;; update with a valid cc number and see if we can support a project
          (browser/click test-plans/update-payment-button)
          (enter-cc-information {:cardnumber test-plans/valid-visa-cc
                                 :exp-date "0120"
                                 :cvc "123"
                                 :postal "11111"})
          (browser/click test-plans/use-card-button)
          ;; support the project at $10 per month
          (browser/wait-until-displayed support-submit-button)
          (taxi/click {:xpath "//label[contains(text(),'$10')]/parent::div"})
          (browser/click support-submit-button)
          ;; check that the confirmation message exists
          (browser/wait-until-displayed (support-message-xpath (now-supporting-at-string "$10.00")))
          (is (browser/exists? (support-message-xpath (now-supporting-at-string "$10.00"))))
          ;; change support to $50 month
          (taxi/click {:xpath "//label[contains(text(),'$50')]/parent::div"})
          (browser/click support-submit-button)
          (browser/wait-until-displayed (support-message-xpath (now-supporting-at-string "$50.00")))
          (is (browser/exists? (support-message-xpath (now-supporting-at-string "$50.00"))))
          ;; is this all the user is paying for?
          (let [user-subscriptions (plans/user-support-subscriptions (users/get-user-by-email email))]
            (is (= 1
                   (count user-subscriptions)))
            (is (= 5000
                   (-> user-subscriptions
                       first
                       :quantity))))
          ;; subscribe at a custom amount of $200
          (browser/click {:xpath "//input[@type='text']"})
          (browser/backspace-clear 5 {:xpath "//input[@type='text']"})
          (browser/set-input-text-per-char {:xpath "//input[@type='text']"} "200")
          (browser/click support-submit-button)
          (browser/wait-until-displayed (support-message-xpath (now-supporting-at-string "$200.00")))
          (is (browser/exists? (support-message-xpath (now-supporting-at-string "$200.00"))))
          ;; is this all the user is paying for?
          (let [user-subscriptions (plans/user-support-subscriptions (users/get-user-by-email email))]
            (is (= 1
                   (count user-subscriptions)))
            (is (= 20000
                   (-> user-subscriptions
                       first
                       :quantity))))
          ;; is there a minimum support level?
          (browser/click {:xpath "//input[@type='text']"})
          (browser/backspace-clear 5 {:xpath "//input[@type='text']"})
          (browser/set-input-text-per-char {:xpath "//input[@type='text']"} "0.99")
          (browser/click support-submit-button)
          (is (browser/exists? (test-plans/error-msg-xpath "Minimum support level is $1.00 per month")))
          ;; cancel support
          (browser/click cancel-support-button)
          (browser/wait-until-displayed stop-support-button)
          (browser/click stop-support-button)
          (browser/wait-until-displayed {:xpath "//h1[text()='Support This Project']"})
          (is (browser/exists? {:xpath "//h1[text()='Support This Project']"}))
          ;;(unsubscribe-user-from-all-support-plans (users/get-user-by-email email))
          (is (empty? (plans/user-support-subscriptions (users/get-user-by-email email))))))
      (finally
        ;; delete the project
        (create-project/delete-current-project)
        ;; log out
        (log-out)
        ;; delete the user
        (browser/delete-test-user)))))
