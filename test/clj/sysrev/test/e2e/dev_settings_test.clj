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

(deftest ^:e2e test-dev-settings
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [email user-id] :as user} (test/create-test-user system)
          api-key (:api-token (user/user-by-email email))]
      (is (seq api-key))
      (is (= "user does not have a have pro account"
             (-> (graphql-request system
                                  [[:__schema [[:mutationType [[:fields [:name]]]]]]]
                                  :api-key api-key)
                 :errors first :message))
          "Basic plan user can't access the sysrev API")
      (is (= "Account Does Not Exist"
             (-> (read-account {:api-key api-key})
                 :errors
                 first
                 :message))
          "Basic plan user doesn't have a datasource account")
      (account/log-in test-resources user)
      (e/go test-resources (str "/user/" user-id "/settings"))
      (testing "Basic plan user can't enable dev account"
        (doto driver
          (et/is-wait-exists :enable-dev-account)
          (-> (ea/disabled? :enable-dev-account) is)
          ;; Guard against https://github.com/insilica/systematic_review/issues/6
          (ea/perform-actions (-> (ea/make-mouse-input)
                                  (ea/add-pointer-click-el (ea/query driver :enable-dev-account))))
          e/wait-until-loading-completes
          (ea/wait 1)
          (et/is-not-exists? :user-api-key)))
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (testing "Pro plan user can enable dev account and view API key"
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
          "Pro user can access the sysrev API")
      (is (= api-key (get-in (read-account {:api-key api-key})
                             [:data :account :apiKey]))
          "Pro user has a datasource account"))))
