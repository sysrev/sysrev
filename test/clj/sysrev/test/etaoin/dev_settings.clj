(ns sysrev.test.etaoin.dev-settings
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [is use-fixtures]]
            [etaoin.api :as etaoin]
            [medley.core :as medley]
            [sysrev.datasource.api :refer [graphql-query read-account]]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.test.etaoin.account :as account]
            [sysrev.test.etaoin.core
             :as
             etaoin-core
             :refer
             [*cleanup-users* *driver* click deftest-etaoin etaoin-fixture fill go]]
            [sysrev.test.graphql.core :refer [graphql-request]]
            [sysrev.user.core :refer [get-user-emails user-by-email]]
            [venia.core :as venia]))

(use-fixtures :once default-fixture)
(use-fixtures :each etaoin-fixture)
(def datasource-url "https://datasource.insilica.co")

(deftest-etaoin happy-path-enable
  (let [user (account/create-account)
        _ (swap! *cleanup-users* conj user)
        api-key (-> user :email user-by-email :api-token)]
    (click @*driver* :user-name-link)
    (click @*driver* :user-settings)
    ;; user can't enable their dev account
    (etaoin/wait-exists @*driver* :enable-dev-account)
    (is (etaoin/disabled? @*driver* :enable-dev-account))
    ;; user can't access the Sysrev API
    (is (= "user does not have a have pro account"
           (-> (graphql-request (venia/graphql-query {:venia/queries [[:__schema [[:mutationType [[:fields [:name]]]]]]]})
                                :api-key api-key)
               :resolved_value
               :data
               first
               :message)))
    ;; user doesn't have a datasource account yet
    (is (= "Account Does Not Exist"
           (-> (read-account {:api-key api-key})
               :errors
               first
               :message)))
    ;; user adds a valid stripe card
    (account/change-user-plan)
    ;; user toggles their dev account
    (click @*driver* :user-name-link)
    (click @*driver* :user-settings)
    (click @*driver* "//input[@id='enable-dev-account']/..")
    ;; check that the ui says the dev account is enabled
    (etaoin/wait-exists @*driver* "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]")
    (is (etaoin/enabled? @*driver* "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]"))
    ;; check that the user can access the Sysrev API
    (is (medley/find-first #(= "importDataset" (:name %))
                           (-> (graphql-request (venia/graphql-query {:venia/queries [[:__schema [[:mutationType [[:fields [:name]]]]]]]})
                                                :api-key api-key)
                               :data :__schema :mutationType :fields)))
    ;; check that the user has an enabled Datasource account
    (is (= api-key
           (get-in (read-account {:api-key api-key})
                   [:data :account :apiKey])))))

(deftest-etaoin account-mutation-tests
  (let [user (account/create-account)
        api-key (-> user :email user-by-email :api-token)]
    ;; change plan and enable dev account
    (account/change-user-plan)
    (click @*driver* :user-name-link)
    (click @*driver* :user-settings)
    (click @*driver* "//input[@id='enable-dev-account']/..")
    ;; check that the ui says the dev account is enabled
    (etaoin/wait-exists @*driver* "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]")
    (is (etaoin/enabled? @*driver* "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]"))
    ;; user can login to datasource
    (let [{:keys [email password]} user
          login-resp (http/post (str datasource-url "/login")
                                {:form-params {:email email
                                               :password password}})]
      (is (get-in login-resp [:cookies "token" :value])))
    ;; change password on sysrev, login to datasource
    ;; with new password
    (click @*driver* :log-out-link)
    (click @*driver* :log-in-link)
    (click @*driver* "//a[contains(text(),'Forgot Password?')]")
    (fill @*driver* "//input[@name='email']" (:email user))
    (click @*driver* "//button[@name='submit']")
    (etaoin/wait-exists @*driver* "//div[contains(text(),'An email has been sent with a link to reset your password.')]")
    (is (etaoin/exists? @*driver* "//div[contains(text(),'An email has been sent with a link to reset your password.')]"))
    (go @*driver* (str "/reset-password/" (-> (user-by-email (:email user)) :reset-code)))
    (fill @*driver* "//input[@name='password']" (str/reverse (:password user)))
    (click @*driver* "//button[@name='submit']")
    (account/login {:email (:email user)
                    :password (str/reverse (:password user))})
    (etaoin/wait-exists @*driver* :log-out-link)
    (is (etaoin/exists? @*driver* :log-out-link))
    (let [{:keys [email password]} user
          login-resp (http/post (str datasource-url "/login")
                                {:form-params {:email email
                                               :password (str/reverse password)}})]
      (is (get-in login-resp [:cookies "token" :value])))
    ;; change primary email address
    (click @*driver* :user-name-link)
    (click @*driver* :user-email)
    (click @*driver* "//button[contains(text(),'Add a New')]")
    (let [new-email (-> (str/split (:email user) #"@") first (str "+alpha@example.com"))
          user-id (:user-id (user-by-email (:email user)))]
      ;; change the email
      (swap! *cleanup-users* conj {:email new-email})
      (fill @*driver* :new-email-address new-email)
      (click @*driver* :new-email-address-submit)
      (etaoin/wait-exists @*driver* (str "//div[contains(@class,'email-unverified') and contains(@class,'label')]/parent::h4[contains(text(),'" new-email "')]"))
      ;; verify the email
      (let [verify-code (->> user-id get-user-emails (medley/find-first #(= new-email (:email %))) :verify-code)]
        (go @*driver* (str "/user/" user-id "/email/" verify-code))
        (etaoin/wait-exists @*driver* (str "//div[contains(@class,'blue') and contains(@class,'label')]/parent::h4[contains(text(),'" new-email "')]"))
        (is (etaoin/exists? @*driver* (str "//div[contains(@class,'blue') and contains(@class,'label')]/parent::h4[contains(text(),'" new-email "')]"))))
      ;; login to datasource with new email and password
      (let [{:keys [password]} user
            login-resp (http/post (str datasource-url "/login")
                                  {:form-params {:email new-email
                                                 :password (str/reverse password)}})]
        (is (get-in login-resp [:cookies "token" :value])))
      ;; disable pro account
      (account/change-user-plan)
      ;; make sure sysrev graphql access is disabled
      (is (= "user does not have a have pro account"
             (-> (graphql-request (venia/graphql-query {:venia/queries [[:__schema [[:mutationType [[:fields [:name]]]]]]]})
                                  :api-key api-key)
                 :resolved_value
                 :data
                 first
                 :message)))
      ;; make sure user can't use datasource graphql
      (is (= "api-token is not enabled or authorized for this transaction"
             (-> (graphql-query (venia/graphql-query
                                 {:venia/queries [[:__schema [[:mutationType [[:fields [:name]]]]]]]})
                                :api-key api-key)
                 :data
                 first
                 :message)))
      ;; make sure user can't login to datasource
      (let [{:keys [password]} user
            login-resp (http/post (str datasource-url "/login")
                                  {:form-params {:email new-email
                                                 :password (str/reverse password)}})]
        (is (= "Account is not enabled"
               (-> login-resp (get-in [:headers "Location"]) (str/split #"error=") second)))))))
