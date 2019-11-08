(ns sysrev.test.web.routes.api.handlers
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [sysrev.api :as api]
            [sysrev.project.core :as project]
            [sysrev.source.core :as source]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :as test :refer [default-fixture database-rollback-fixture]]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.test.browser.core :as b]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest create-project-test
  (let [handler (sysrev-handler)
        {:keys [email password]} b/test-login
        search-term "foo bar"]
    ;; create user
    (b/create-test-user)
    ;; login this user
    (let [api-token (-> (handler
                         (-> (mock/request :get "/web-api/get-api-token")
                             (mock/query-string {:email email
                                                 :password password})))
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
          new-project-id (get-in create-project-response [:result :project :project-id])
          search-query-result (pubmed/get-search-query-response search-term 1)
          _meta (source/make-source-meta
                 :pubmed {:search-term search-term
                          :search-count (count (:pmids search-query-result))})]
      ;; create a project for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0 (project/project-article-count new-project-id))))))

(deftest transfer-project-test
  (let [handler (sysrev-handler)
        {:keys [email password]} b/test-login
        user-foo {:email "foo@bar.com"
                  :password "foobar"}
        user-baz {:email "baz@qux.com"
                  :password "bazqux"}
        user-quux {:email "quux@corge.com"
                   :password "quuxcorge"}
        _ (b/create-test-user)
        _ (b/create-test-user :email (:email user-foo)
                              :password (:password user-foo))
        _ (b/create-test-user :email (:email user-baz)
                              :password (:password user-baz))
        _ (b/create-test-user :email (:email user-quux)
                              :password (:password user-quux))
        admin-id (user-by-email email :user-id)
        foo-user-id (user-by-email (:email user-foo) :user-id)
        baz-user-id (user-by-email (:email user-baz) :user-id)
        quux-user-id (user-by-email (:email user-quux) :user-id)
        group-name "Baz Business"
        group-id (:id (api/create-org! baz-user-id group-name))]
    ;; make test user is an admin
    (user/set-user-permissions admin-id ["user" "admin"])
    ;; login this user
    (let [api-token (-> (handler
                         (-> (mock/request :get "/web-api/get-api-token")
                             (mock/query-string {:email email
                                                 :password password})))
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
                            :user-id foo-user-id
                            :api-token api-token})))))
      ;; is the user now the owner of the project?
      (is (= foo-user-id
             (:user-id (project/get-project-owner project-id))))
      ;; add another user to this org
      (api/set-user-group! quux-user-id group-name true)
      ;; make them an owner as well
      (api/set-user-group-permissions! quux-user-id group-id ["owner"])
      ;; transfer the project to this org
      (-> (handler
           (-> (mock/request :post "/web-api/transfer-project")
               (mock/body (json/write-str
                           {:project-id project-id
                            :group-id group-id
                            :api-token api-token})))))
      ;; the org is now the owner of the project
      (is (= group-id
             (:group-id (project/get-project-owner project-id)))))))
