(ns sysrev.test.e2e.project-invite-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.config :refer [env]]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.project.member :as member]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as e-project]))

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

(deftest ^:e2e test-valid-emails
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources "Test Valid Invites")]
      (member/add-project-member project-id user-id :permissions ["owner" "admin" "member"])
      (e/refresh driver)
      (e/go-project test-resources project-id "/users")
      (testing "Can send emails in bulk"
        (doseq [separator valid-separators
                emails (partition 3 valid-emails)
                :let [ct (count emails)]]
          (doto driver
            (ea/wait-visible :bulk-invite-emails) ;; Throw to exit doseq early if this fails
            (et/is-fill-visible :bulk-invite-emails (str/join separator emails))
            (et/is-wait-visible [{:fn/has-class :emails-status}
                                 {:fn/text (if (= 1 ct) "1 email" (str ct " emails"))}])
            (et/is-click-visible :send-bulk-invites-button)
            (et/is-wait-visible {:fn/has-classes [:alert-message :success]
                                 :fn/text (str ct " invitation(s) successfully sent!")})
            (et/is-wait-visible {:css ".alert-message.success"})))))))

(deftest ^:e2e test-invalid-emails
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources "Test Invalid Invites")]
      (member/add-project-member project-id user-id :permissions ["owner" "admin" "member"])
      (e/refresh driver)
      (e/go-project test-resources project-id "/users")
      (testing "Can't send to invalid email addresses"
        (doto driver
          (et/is-fill-visible :bulk-invite-emails (str/join (first valid-separators) invalid-emails))
          (et/is-wait-visible {:css "#send-bulk-invites-button.disabled"})
          (et/is-wait-visible {:css ".bulk-invites-form .ui.label.emails-status"
                               :fn/text "0 emails"}))))))

(deftest ^:e2e test-max-emails
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [ ;; one more than max allowed
          emails (->> (range 0 (inc (:max-bulk-invitations env)))
                      (map #(str "email" % "@example.com")))
          user-id (account/log-in test-resources (test/create-test-user system))
          project-id (e-project/create-project! test-resources "Test Max Emails")]
      (member/add-project-member project-id user-id :permissions ["owner" "admin" "member"])
      (e/refresh driver)
      (e/go-project test-resources project-id "/users")
      (testing "Error message is displayed when sending too many emails"
        (doto driver
          (et/is-fill-visible :bulk-invite-emails (str/join (first valid-separators) emails))
          (et/is-click-visible :send-bulk-invites-button)
          (et/is-wait-visible {:fn/has-classes [:alert-message :error]
                               :fn/text (str "Maximum emails allowed are " (:max-bulk-invitations env))}))))))
