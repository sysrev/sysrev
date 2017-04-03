(ns sysrev.test.api
  (:require [clojure.test :refer :all]
            [clojure.spec :as s]
            [clojure.spec.test :as t]
            [clojure.tools.logging :as log]
            [sysrev.shared.spec.core :as sc]
            [sysrev.test.core :refer
             [default-fixture completes? get-selenium-config]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.web.routes.api.core :refer [webapi-get webapi-post]]
            sysrev.web.routes.api.handlers
            [sysrev.db.queries :as q]))

(use-fixtures :once default-fixture)

(deftest test-get-api-token
  (let [url (:url (get-selenium-config))
        email "test+apitest@insilica.co"
        password "1234567890"
        {:keys [user-id]} (users/create-user email password)]
    (try
      (let [response (webapi-get "get-api-token"
                                 {:email email :password password}
                                 :url url)]
        (is (contains? response :result))
        (is (string? (-> response :result :api-token))))
      (finally
        (when user-id (users/delete-user user-id))))))

(deftest test-import-pmids
  (let [url (:url (get-selenium-config))
        email "test+apitest@insilica.co"
        password "1234567890"
        {:keys [user-id api-token]} (users/create-user email password)
        {:keys [project-id]
         :as project} (project/create-project "test-import-pmids")]
    (try
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345 12346]}
                                  :url url)]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (finally
        (when user-id (users/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-check-allow-answers
  (let [url (:url (get-selenium-config))
        email "test+apitest@insilica.co"
        password "1234567890"
        {:keys [user-id api-token]} (users/create-user email password)
        {:keys [project-id]
         :as project} (project/create-project "test-check-allow-answers")]
    (try
      (labels/add-label-entry-boolean
       project-id {:name "include" :question "include?" :short-label "Include"
                   :inclusion-value true :required true})
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345 12346]}
                                  :url url)]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (let [article-id
            (-> (q/select-article-where
                 project-id [:= :public-id "12345"] [:article-id])
                do-query first :article-id)
            label-id
            (-> (q/select-label-where project-id true [:label-id])
                do-query first :label-id)]
        (is (s/valid? ::sc/article-id article-id))
        (is (s/valid? ::sc/label-id label-id))
        (labels/set-user-article-labels
         user-id article-id {label-id true} false)
        (let [response (webapi-post "import-pmids"
                                    {:api-token api-token
                                     :project-id project-id
                                     :pmids [12347]}
                                    :url url)]
          (is (contains? response :error))
          (is (not (-> response :result :success)))))
      (finally
        (when user-id (users/delete-user user-id))
        (when project-id (project/delete-project project-id))))))
