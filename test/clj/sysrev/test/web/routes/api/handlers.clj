(ns sysrev.test.web.routes.api.handlers
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [sysrev.project.core :as project]
            [sysrev.source.core :as source]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :as test :refer [default-fixture database-rollback-fixture]]
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
    (let [web-api-token (-> (handler
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
                                 :api-token web-api-token}))
                    (mock/header "Content-Type" "application/json")))
              :body
              (json/read-str :key-fn keyword))
          new-project-id (get-in create-project-response [:result :project :project-id])
          search-query-result (pubmed/get-search-query-response search-term 1)
          meta (source/make-source-meta
                :pubmed {:search-term search-term
                         :search-count (count (:pmids search-query-result))})]
      ;; create a project for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0 (project/project-article-count new-project-id))))))
