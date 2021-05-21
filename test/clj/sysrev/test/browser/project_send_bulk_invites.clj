(ns sysrev.test.browser.project-send-bulk-invites
  (:require [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.xpath :as x]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.config :refer [env]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def max-bulk-invitations (:max-bulk-invitations env))

(def valid-emails
  ["name@example.com"
   "firstname.lastname@example.com"
   "name@app.company.net"
   "firstname+lastname@example.com"
   "firstname-lastname@example.com"
   "name@127.0.0.1"
   "1234567890@example.com"
   "name@app.example-dash.com"
   "name@example-dash.com"])

(def invalid-emails
  ["noat.example.com"
   "multiple@at@example.com"
   ".firstdot@example.com"
   "lastdot.@example.com"
   "@example.com"
   "double..dot@example.com"
   "email@noext"
   "name@-firstdash.com"
   "namel@example..com"])

(def valid-separators [" " "," "\n"])

(deftest-browser test-valid-emails
  true test-user
  [input "#bulk-invite-emails"
   success-notification ".ui.alert-message.success"
   send-button "#send-bulk-invites-button"
   test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Send Bulk Invites Test (1)")
      (pm/import-pubmed-search-via-db "foo bar")
      ;; project description
      (b/click (x/project-menu-item :users) :delay 200)
      ;; test emails
      (doall
        (for [separator valid-separators
              emails (partition 3 valid-emails)]
          (do
            (b/set-input-text input (str/join separator emails) :delay 50)
            (b/click send-button :delay 150)
            (b/wait-until-displayed success-notification)
            (b/click success-notification :delay 100)))))
  :cleanup (do
             (nav/delete-current-project)
             (nav/log-out)))


(deftest-browser test-invalid-emails
  (test/db-connected?) test-user []
  (do (nav/log-in (:email test-user))
      (nav/new-project "Send Bulk Invites Test (2)")
      (pm/import-pubmed-search-via-db "foo bar")
      (b/click (x/project-menu-item :users) :delay 200)
      (log/info "entering emails")
      (b/set-input-text "#bulk-invite-emails"
                        (str/join (first valid-separators) invalid-emails))
      (log/info "checking result")
      (b/displayed? "#send-bulk-invites-button.disabled")
      (b/text-is? ".bulk-invites-form .ui.label.emails-status" "0 emails"))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)))

(deftest-browser test-max-emails
  (test/db-connected?) test-user
  [ ;; one more than max allowed
   emails (->> (range 0 (inc max-bulk-invitations))
               (map #(str "email" % "@example.com")))]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Send Bulk Invites Test (3)")
      (pm/import-pubmed-search-via-db "foo bar")
      (b/click (x/project-menu-item :users) :delay 200)
      (log/infof "entering %d emails" (count emails))
      (b/set-input-text "#bulk-invite-emails"
                        (str/join (first valid-separators) emails))
      (log/info "submitting emails")
      (b/click "#send-bulk-invites-button" :delay 200)
      (log/info "waiting for error notification")
      (b/wait-until-displayed ".ui.alert-message.error"))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)))
