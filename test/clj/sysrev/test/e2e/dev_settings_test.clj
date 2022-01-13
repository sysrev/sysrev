(ns sysrev.test.e2e.dev-settings-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [medley.core :as medley]
   [sysrev.datasource.api :as ds-api :refer [read-account]]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.core :as test :refer [graphql-request]]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.user.core :refer [user-by-email]]))

(deftest ^:kaocha/pending ^:e2e happy-path-enable
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          api-key (-> user :email user-by-email :api-token)]
      (account/log-in test-resources user)
      (doto driver
        (et/click-visible :user-name-link)
        (et/click-visible :user-settings)
        ;; user can't enable their dev account
        (e/wait-exists :enable-dev-account)
        (-> (ea/disabled? :enable-dev-account) is))
      ;; user can't access the Sysrev API
      (is (= "user does not have a have pro account"
             (-> (graphql-request system
                                  [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                                  :api-key api-key)
                 :resolved_value :data first :message)))
      ;; user doesn't have a datasource account yet
      (is (= "Account Does Not Exist"
             (-> (read-account {:api-key api-key})
                 :errors
                 first
                 :message)))
      ;; user adds a valid stripe card
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (doto driver
        ;; user toggles their dev account
        (et/click-visible :user-name-link)
        (et/click-visible :user-settings)
        (et/click-visible "//input[@id='enable-dev-account']/..")
        ;; check that the ui says the dev account is enabled
        (-> (e/enabled? driver "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]")
            is))
      ;; check that the user can access the Sysrev API
      (is (-> (graphql-request system
                               [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                               :api-key api-key)
              (get-in [:data :__schema :mutationType :fields])
              (->> (medley/find-first #(= "importDataset" (:name %))))))
      ;; check that the user has an enabled Datasource account
      (is (= api-key (get-in (read-account {:api-key api-key})
                             [:data :account :apiKey]))))))

(deftest ^:kaocha/pending ^:e2e account-mutation-tests
  #_(e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user (test/create-test-user system)
          api-key (-> user :email user-by-email :api-token)
          new-email (-> (str/split (:email user) #"@") first (str "+alpha@example.com"))
          user-id (:user-id (user-by-email (:email user)))]
      (account/log-in test-resources user)
      ;; change plan and enable dev account
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (et/click-visible :user-name-link)
      (et/click-visible :user-settings)
      (et/click-visible "//input[@id='enable-dev-account']/..")
      ;; check that the ui says the dev account is enabled
      (is (e/enabled? "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]"))
      ;; user can login to datasource
      (let [{:keys [email password]} user
            login-resp (http/post (str (ds-api/ds-host) "/login")
                                  {:form-params {:email email
                                                 :password password}})]
        (is (get-in login-resp [:cookies "token" :value])))
      ;; change password on sysrev, login to datasource
      ;; with new password
      (et/click-visible :log-out-link)
      (et/click-visible :log-in-link)
      (et/click-visible "//a[contains(text(),'Forgot Password?')]")
      (e/fill "//input[@name='email']" (:email user) :clear? true)
      (et/click-visible "//button[@name='submit']")
      (e/exists? "//div[contains(text(),'An email has been sent with a link to reset your password.')]")
      (e/go (str "/reset-password/" (-> (user-by-email (:email user)) :reset-code)))
      (e/fill "//input[@name='password']" (str/reverse (:password user)) :clear? true)
      (et/click-visible "//button[@name='submit']")
      (account/log-in {:email (:email user)
                       :password (str/reverse (:password user))})
      (is (e/exists? :log-out-link))
      (let [{:keys [email password]} user
            login-resp (http/post (str (ds-api/ds-host) "/login")
                                  {:form-params {:email email
                                                 :password (str/reverse password)}})]
        (is (get-in login-resp [:cookies "token" :value])))
      ;; change primary email address
      (et/click-visible :user-name-link)
      (et/click-visible :user-email)
      (et/click-visible "//button[contains(text(),'Add a New')]")
      (e/fill :new-email-address new-email :clear? true)
      (et/click-visible :new-email-address-submit)
      (e/wait-exists (str "//div[contains(@class,'email-unverified') and contains(@class,'label')]"
                          "/parent::h4[contains(text(),'" new-email "')]"))
      ;; verify the email
      (let [verify-code (->> (get-user-emails user-id)
                             (medley/find-first #(= new-email (:email %)))
                             :verify-code)]
        (e/go (str "/user/" user-id "/email/" verify-code))
        (is (e/exists? (str "//div[contains(@class,'blue') and contains(@class,'label')]"
                            "/parent::h4[contains(text(),'" new-email "')]"))))
      ;; login to datasource with new email and password
      (let [{:keys [password]} user
            login-resp (http/post (str (ds-api/ds-host) "/login")
                                  {:form-params {:email new-email
                                                 :password (str/reverse password)}})]
        (is (get-in login-resp [:cookies "token" :value])))
      ;; disable pro account
      (test/change-user-plan! system user-id "Basic")
      ;; make sure sysrev graphql access is disabled
      (is (= "user does not have a have pro account"
             (-> (graphql-request [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                                  :api-key api-key)
                 :resolved_value :data first :message)))
      ;; make sure user can't use datasource graphql
      (is (= "api-token is not enabled or authorized for this transaction"
             (-> (graphql-query [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                                :api-key api-key)
                 :data first :message)))
      ;; make sure user can't login to datasource
      (let [{:keys [password]} user
            login-resp (http/post (str (ds-api/ds-host) "/login")
                                  {:form-params {:email new-email
                                                 :password (str/reverse password)}})]
        (is (= "Account is not enabled"
               (-> login-resp (get-in [:headers "Location"]) (str/split #"error=") second)))))))
