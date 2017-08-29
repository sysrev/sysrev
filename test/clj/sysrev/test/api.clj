(ns sysrev.test.api
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
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
            [sysrev.db.queries :as q]
            [sysrev.shared.util :refer [in?]]))

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
        {:keys [user-id api-token]}
        (users/create-user email password)
        {:keys [project-id] :as project}
        (project/create-project "test-import-pmids")]
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

(deftest test-copy-articles
  (let [url (:url (get-selenium-config))
        email "test+apitest@insilica.co"
        password "1234567890"
        {:keys [user-id api-token]}
        (users/create-user email password)
        {:keys [project-id] :as project}
        (project/create-project "test-copy-articles")
        dest-project
        (project/create-project "test-copy-articles-dest")]
    (try
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345]}
                                  :url url)]
        (is (true? (-> response :result :success)))
        (is (= 1 (-> response :result :project-articles)))
        (let [article-id (-> (q/select-project-articles project-id [:article-id])
                             (do-query) first :article-id)]
          (is (not (nil? article-id)))
          (let [response (webapi-post "copy-articles"
                                      {:api-token api-token
                                       :project-id (:project-id dest-project)
                                       :src-project-id project-id
                                       :article-ids [article-id]}
                                      :url url)]
            (is (= 1 (-> response :result :success))
                (str "response = " (pr-str response))))))
      (finally
        (when user-id (users/delete-user user-id))
        (when project-id (project/delete-project project-id))
        (when dest-project (project/delete-project (:project-id dest-project)))))))

(deftest test-import-pmid-nct-arms
  (let [url (:url (get-selenium-config))
        email "test+apitest@insilica.co"
        password "1234567890"
        {:keys [user-id api-token]}
        (users/create-user email password)
        {:keys [project-id] :as project}
        (project/create-project "test-import-pmid-nct-arms")]
    (try
      (let [response (webapi-post
                      "import-pmid-nct-arms"
                      {:api-token api-token
                       :project-id project-id
                       :arm-imports [{:pmid 12345
                                      :nct "NCT67890"
                                      :arm-name "arm #1"
                                      :arm-desc "first arm"}
                                     {:pmid 12345
                                      :nct "NCT67890"
                                      :arm-name "arm #2"
                                      :arm-desc "second arm"}]}
                      :url url)]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (finally
        (when user-id (users/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-create-project
  (let [url (:url (get-selenium-config))
        email "test+apitest@insilica.co"
        password "1234567890"
        {:keys [user-id api-token]}
        (users/create-user email password :permissions ["user" "admin"])
        project-name "test-create-project"]
    (try
      (let [response (webapi-post
                      "create-project"
                      {:api-token api-token
                       :project-name project-name
                       :add-self? true}
                      :url url)
            {:keys [success project]} (:result response)
            {:keys [project-id name]} project]
        (try
          (do (is (true? success))
              (is (integer? project-id))
              (is (string? name))
              (is (= name project-name))
              (is (q/query-project-by-id project-id [:project-id]))
              (is (in? (->> (q/query-project-labels project-id [:name])
                            (map :name))
                       "overall include"))
              (is (= (:name (q/query-project-by-id project-id [:name]))
                     project-name)))
          (finally
            (when project-id
              (project/delete-project project-id)))))
      (finally
        (when user-id (users/delete-user user-id))))))

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
         user-id article-id {label-id true}
         :imported? false
         :change? false
         :confirm? true
         :resolve? false)
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
