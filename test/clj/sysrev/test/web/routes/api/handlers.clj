(ns sysrev.test.web.routes.api.handlers
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [sysrev.db.users :as users]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [ring.mock.request :as mock]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest create-project-test
  (let [handler (sysrev-handler)
        email "foo@bar.com"
        password "foobar"]
    ;; create user
    (users/create-user email password :project-id 100)
    ;; login this user
    (let [web-api-token (-> (handler
                             (-> (mock/request :get "/web-api/get-api-token")
                                 (mock/json-body {:email email
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
              (json/read-str :key-fn keyword))]
      ;; create a project for this user
      (is (-> create-project-response
              :result
              :success)))))
