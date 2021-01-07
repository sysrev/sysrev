(ns sysrev.test.browser.project-send-bulk-invites
  (:require [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.core :refer [default-fixture]]
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
   success-notification ".ui.toast.success"
   send-button "#send-bulk-invites-button"
   test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Send Bulk Invites Test")
      (pm/import-pubmed-search-via-db "foo bar")
      ;; project description
      (b/click "#project a.item.users")
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      ;; enter emails
      (b/wait-until-displayed input)
      ;; test emails
      (doall
        (for [separator valid-separators
              emails (partition 3 valid-emails)]
          (do
            (b/set-input-text input (str/join separator emails) :delay 50)
            (b/click send-button)
            (b/wait-until-displayed success-notification)
            (b/click success-notification :delay 100)))))
  :cleanup (do
             (nav/delete-current-project)
             (nav/log-out)))


(deftest-browser test-invalid-emails
  true test-user
  [input "#bulk-invite-emails"
   success-notification ".ui.toast.success"
   send-button "#send-bulk-invites-button"
   test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Send Bulk Invites Test")
      (pm/import-pubmed-search-via-db "foo bar")
      ;; project description
      (b/click "#project a.item.users")
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      ;; enter emails
      (b/wait-until-displayed input)
      ;; test emails
      (b/set-input-text input (str/join (first valid-separators) invalid-emails) :delay 50)
      (b/is-disabled send-button))
  :cleanup (do
             (nav/delete-current-project)
             (nav/log-out)))

(deftest-browser test-max-emails
  true test-user
  [input "#bulk-invite-emails"
   failure-notification ".ui.toast.error"
   send-button "#send-bulk-invites-button"
   test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)
   emails (take (inc max-bulk-invitations) valid-emails)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Send Bulk Invites Test")
      (pm/import-pubmed-search-via-db "foo bar")
      ;; project description
      (b/click "#project a.item.users")
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      ;; enter emails
      (b/wait-until-displayed input)
      ;; test emails
      (b/set-input-text input (str/join (first valid-separators) emails) :delay 50)
      (b/click send-button)
      (b/wait-until-displayed failure-notification 20000)
      (b/click failure-notification :delay 100))
  :cleanup (do
             (nav/delete-current-project)
             (nav/log-out)))





