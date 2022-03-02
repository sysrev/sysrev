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
   [sysrev.user.interface :as user]))

(deftest ^:e2e test-dev-settings-basic
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [email user-id] :as user} (test/create-test-user system)
          api-key (:api-token (user/user-by-email email))]
      (is (seq api-key))
      (is (= "dev account is not enabled for that user"
             (-> (graphql-request system
                                  [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                                  :api-key api-key)
                 :errors first :message))
          "User can't access the sysrev API without dev account enabled")
      (is (= "Account Does Not Exist"
             (-> (read-account {:api-key api-key})
                 :errors
                 first
                 :message))
          "User doesn't have a datasource account without dev account enabled")
      (account/log-in test-resources user)
      (e/go test-resources (str "/user/" user-id "/settings"))
      (testing "Basic plan user can enable dev account and view API key"
        (doto driver
          e/refresh
          (ea/perform-actions (-> (ea/make-mouse-input)
                                  (ea/add-pointer-click-el (ea/query driver :enable-dev-account))))
          (et/is-wait-visible :user-api-key)
          (et/is-wait-visible {:fn/has-text api-key})
          (et/is-wait-visible "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]")))
      (is (-> (graphql-request system
                               [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                               :api-key api-key)
              (get-in [:data :__schema :mutationType :fields])
              (->> (medley/find-first #(= "importDataset" (:name %)))))
          "Basic user can access the sysrev API")
      (is (= api-key (get-in (read-account {:api-key api-key})
                             [:data :account :apiKey]))
          "Basic user has a datasource account"))))

(deftest ^:e2e test-dev-settings-pro
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [email user-id] :as user} (test/create-test-user system)
          api-key (:api-token (user/user-by-email email))]
      (is (seq api-key))
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (account/log-in test-resources user)
      (e/go test-resources (str "/user/" user-id "/settings"))
      (testing "Pro plan user can enable dev account and view API key"
        (doto driver
          (ea/perform-actions (-> (ea/make-mouse-input)
                                  (ea/add-pointer-click-el (ea/query driver :enable-dev-account))))
          (et/is-wait-visible :user-api-key)
          (et/is-wait-visible {:fn/has-text api-key})
          (et/is-wait-visible "//input[@id='enable-dev-account']/parent::div[contains(@class,'checked')]")))
      (is (-> (graphql-request system
                               [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                               :api-key api-key)
              (get-in [:data :__schema :mutationType :fields])
              (->> (medley/find-first #(= "importDataset" (:name %)))))
          "Pro user can access the sysrev API")
      (is (= api-key (get-in (read-account {:api-key api-key})
                             [:data :account :apiKey]))
          "Pro user has a datasource account"))))
