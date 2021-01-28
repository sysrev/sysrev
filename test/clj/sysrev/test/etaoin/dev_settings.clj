(ns sysrev.test.etaoin.dev-settings
  (:require [clojure.test :refer [is use-fixtures]]
            [etaoin.api :as etaoin]
            [medley.core :as medley]
            [sysrev.datasource.api :refer [delete-account! read-account]]
            [sysrev.test.browser.core :refer [delete-test-user]]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.test.etaoin.account :as account]
            [sysrev.test.etaoin.core
             :as
             etaoin-core
             :refer
             [*driver* click deftest-etaoin etaoin-fixture]]
            [sysrev.test.graphql.core :refer [graphql-request]]
            [sysrev.user.core :refer [user-by-email]]
            [venia.core :as venia]))

(use-fixtures :once default-fixture)
(use-fixtures :each etaoin-fixture)

(deftest-etaoin happy-path-enable
  (let [user (account/create-account)
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
                   [:data :account :apiKey])))
    ;; cleanup
    ;; delete the Datasource account
    (is (get-in (delete-account! user) [:data :deleteAccount]))
    ;; delete the Sysrev Account
    (delete-test-user :email (:email user))))
