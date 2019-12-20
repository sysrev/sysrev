(ns sysrev.test.web.routes.api.handlers
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [sysrev.test.core :as test :refer [default-fixture database-rollback-fixture]]
            [sysrev.test.browser.core :as b]
            [sysrev.api :as api]
            [sysrev.project.core :as project]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.user.core :as user]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest create-project-test
  (let [handler (sysrev-handler)
        {:keys [email]} (b/create-test-user)
        api-token (-> (handler
                       (-> (mock/request :get "/web-api/get-api-token")
                           (mock/query-string {:email email :password b/test-password})))
                      :body
                      (json/read-str :key-fn keyword)
                      :result
                      :api-token)
        create-project-response
        (-> (handler
             (->  (mock/request :post "/web-api/create-project")
                  (mock/body (json/write-str
                              {:project-name "The taming of the foo"
                               :api-token api-token}))
                  (mock/header "Content-Type" "application/json")))
            :body
            (json/read-str :key-fn keyword))
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
                        :body
                        (json/read-str :key-fn keyword)
                        :result
                        :api-token)
          create-project-response
          (-> (handler
               (->  (mock/request :post "/web-api/create-project")
                    (mock/body (json/write-str
                                {:project-name "Baz Qux Research"
                                 :api-token api-token}))
                    (mock/header "Content-Type" "application/json")))
              :body
              (json/read-str :key-fn keyword))
          project-id (get-in create-project-response [:result :project :project-id])]
      ;; was the project created?
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0 (project/project-article-count project-id)))
      ;; transfer project to foo
      (-> (handler
           (-> (mock/request :post "/web-api/transfer-project")
               (mock/body (json/write-str
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
               (mock/body (json/write-str
                           {:project-id project-id
                            :group-id group-id
                            :api-token api-token})))))
      ;; the org is now the owner of the project
      (is (= group-id (:group-id (project/get-project-owner project-id)))))))
