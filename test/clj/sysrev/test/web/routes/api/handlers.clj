(ns sysrev.test.web.routes.api.handlers
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ring.mock.request :as mock]
            [sysrev.test.core :as test :refer [default-fixture database-rollback-fixture
                                               sysrev-handler]]
            [sysrev.test.browser.core :as b]
            [sysrev.api :as api]
            [sysrev.db.queries :as q]
            [sysrev.group.core :as group]
            [sysrev.notification.interface :as notification]
            [sysrev.project.core :as project]
            [sysrev.user.core :as user]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest create-project-test
  (let [handler (sysrev-handler)
        {:keys [email]} (b/create-test-user)
        api-token (-> (handler
                       (-> (mock/request :get "/web-api/get-api-token")
                           (mock/query-string {:email email :password b/test-password})))
                      :body util/read-json :result :api-token)
        create-project-response
        (-> (handler
             (->  (mock/request :post "/web-api/create-project")
                  (mock/body (util/write-json
                              {:project-name "The taming of the foo"
                               :api-token api-token}))
                  (mock/header "Content-Type" "application/json")))
            :body util/read-json)
        new-project-id (get-in create-project-response [:result :project :project-id])]
    ;; create a project for this user
    (is (get-in create-project-response [:result :success]))
    ;; get the article count, should be 0
    (is (= 0 (project/project-article-count new-project-id)))))

(deftest transfer-project-test
  (let [handler (sysrev-handler)
        test-user (b/create-test-user)
        user-foo (b/create-test-user :email "foo@bar.com" :password "foobar")
        user-baz (b/create-test-user :email "baz@qux.com" :password "bazqux")
        user-quux (b/create-test-user :email "quux@corge.com" :password "quuxcorge")
        group-name "Baz Business"
        group-id (:id (api/create-org! (:user-id user-baz) group-name))]
    ;; make test user is an admin
    (user/set-user-permissions (:user-id test-user) ["user" "admin"])
    ;; login this user
    (let [api-token (-> (handler
                         (-> (mock/request :get "/web-api/get-api-token")
                             (mock/query-string {:email (:email test-user)
                                                 :password (:password test-user)})))
                        :body util/read-json :result :api-token)
          create-project-response
          (-> (handler
               (->  (mock/request :post "/web-api/create-project")
                    (mock/body (util/write-json
                                {:project-name "Baz Qux Research"
                                 :api-token api-token}))
                    (mock/header "Content-Type" "application/json")))
              :body util/read-json)
          project-id (get-in create-project-response [:result :project :project-id])]
      ;; was the project created?
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0 (project/project-article-count project-id)))
      ;; transfer project to foo
      (-> (handler
           (-> (mock/request :post "/web-api/transfer-project")
               (mock/body (util/write-json
                           {:project-id project-id
                            :user-id (:user-id user-foo)
                            :api-token api-token})))))
      ;; is the user now the owner of the project?
      (is (= (:user-id user-foo)
             (:user-id (project/get-project-owner project-id))))
      ;; add another user to this org
      (api/set-user-group! (:user-id user-quux) group-name true)
      ;; make them an owner as well
      (api/set-user-group-permissions! (:user-id user-quux) group-id ["owner"])
      ;; transfer the project to this org
      (-> (handler
           (-> (mock/request :post "/web-api/transfer-project")
               (mock/body (util/write-json
                           {:project-id project-id
                            :group-id group-id
                            :api-token api-token})))))
      ;; the org is now the owner of the project
      (is (= group-id (:group-id (project/get-project-owner project-id))))
      ;; delete the group
      (group/delete-group! group-id))))

(deftest create-notification-test
  (let [handler (sysrev-handler)
        test-user (b/create-test-user)
        ;; make test user an admin
        _ (user/set-user-permissions (:user-id test-user) ["user" "admin"])
        ;; login this user
        api-token (-> (handler
                       (-> (mock/request :get "/web-api/get-api-token")
                           (mock/query-string {:email (:email test-user)
                                               :password (:password test-user)})))
                      :body util/read-json :result :api-token)
        create-notification-response
        (-> (handler
             (->  (mock/request :post "/web-api/create-notification")
                  (mock/body (util/write-json
                              {:api-token api-token
                               :text "Test-System-Notification"
                               :uri "/test-system-uri"
                               :type :system}))
                  (mock/header "Content-Type" "application/json")))
            :body util/read-json)
        {:keys [notification-id]} (:result create-notification-response)
        subscriber-id (notification/subscriber-for-user
                       (:user-id test-user)
                       :create? true
                       :returning :subscriber-id)]
    ;; was the notification created?
    (is (get-in create-notification-response [:result :success]))
    (is (= {:text "Test-System-Notification"
            :type "system"
            :uri "/test-system-uri"}
           (q/find-one :notification
                       {:notification-id notification-id}
                       :content)))
    (is (= {:text "Test-System-Notification"
            :type :system
            :uri "/test-system-uri"}
           (-> subscriber-id notification/unviewed-system-notifications
               first :content)))))
