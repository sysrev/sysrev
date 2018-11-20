(ns sysrev.test.web.routes.api.handlers
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [sysrev.db.project :as project]
            [sysrev.source.core :as sources]
            [sysrev.db.users :as users]
            [sysrev.pubmed :as pubmed]
            [sysrev.import.pubmed :as i-pubmed]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [sysrev.test.browser.core :refer [test-login create-test-user]]
            [ring.mock.request :as mock]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest create-project-test
  (let [handler (sysrev-handler)
        {:keys [email password]} test-login
        search-term "foo bar"]
    ;; create user
    (create-test-user)
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
          meta (sources/make-source-meta
                :pubmed {:search-term search-term
                         :search-count (count (:pmids search-query-result))})]
      ;; create a project for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0
             (project/project-article-count new-project-id)))
      ;; add articles to this project
      (i-pubmed/import-pmids-to-project-with-meta! (:pmids search-query-result) new-project-id meta)
      ;; Does the new project have the correct amount of articles?
      ;; I would like a 'get-project' route
      ;; delete this project
      #_ (is (-> (handler
                  (-> (mock/request :post "/web-api/delete-project")
                      (mock/body (json/write-str
                                  {:project-id new-project-id
                                   :api-token web-api-token}))
                      (mock/header "Content-Type" "application/json")))
                 :body
                 (json/read-str :key-fn keyword)
                 :result
                 :success)))))
