(ns sysrev.test.api
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [sysrev.shared.spec.core :as sc]
            [sysrev.test.core :refer
             [default-fixture completes? get-selenium-config db-connected?]]
            [sysrev.test.browser.core :refer [test-login create-test-user]]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.user.core :as user]
            [sysrev.project.core :as project]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            [sysrev.web.routes.api.core :refer [webapi-get webapi-post]]
            sysrev.web.routes.api.handlers
            [sysrev.api :as api]
            [sysrev.shared.util :refer [in?]]))

(use-fixtures :once default-fixture)

(deftest test-get-api-token
  (let [url (:url (get-selenium-config))
        {:keys [email password]} test-login
        {:keys [user-id]} (create-test-user)]
    (try
      (let [response (webapi-get "get-api-token"
                                 {:email email :password password}
                                 :url url)]
        (is (contains? response :result))
        (is (string? (-> response :result :api-token))))
      (finally
        (when user-id (user/delete-user user-id))))))

(deftest test-import-pmids
  (let [url (:url (get-selenium-config))
        {:keys [user-id api-token]} (create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id] :as project} (project/create-project "test-import-pmids")]
    (try
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345 12346]}
                                  :url url)]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (finally
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-import-article-text
  (let [url (:url (get-selenium-config))
        {:keys [user-id api-token]} (create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id] :as project} (project/create-project "test-import-article-text")]
    (try
      (let [response (webapi-post "import-article-text"
                                  {:api-token api-token
                                   :project-id project-id
                                   :articles [{:primary-title "article 1"
                                               :abstract "abstract text 1"}
                                              {:primary-title "abstract 2"
                                               :abstract "abstract text 2"}]}
                                  :url url)]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :attempted)))
        (is (= 2 (-> response :result :project-articles))))
      (finally
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

#_
(deftest test-copy-articles
  (let [url (:url (get-selenium-config))
        {:keys [user-id api-token]} (create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id] :as project} (project/create-project "test-copy-articles")
        dest-project (project/create-project "test-copy-articles-dest")]
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
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))
        (when dest-project (project/delete-project (:project-id dest-project)))))))

#_
(deftest test-import-pmid-nct-arms
  (let [url (:url (get-selenium-config))
        {:keys [user-id api-token]} (create-test-user)
        {:keys [project-id] :as project} (project/create-project "test-import-pmid-nct-arms")]
    (try
      (let [response (webapi-post "import-pmid-nct-arms"
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
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-create-project
  (let [url (:url (get-selenium-config))
        {:keys [user-id api-token]} (create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        project-name "test-create-project"]
    (try
      (let [response (webapi-post "create-project"
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
              (is (in? (->> (q/select-label-where project-id true [:name])
                            do-query (map :name))
                       "overall include"))
              (is (= (:name (q/query-project-by-id project-id [:name]))
                     project-name)))
          (finally
            (when project-id
              (project/delete-project project-id)))))
      (finally
        (when user-id (user/delete-user user-id))))))

(deftest test-check-allow-answers
  (let [url (:url (get-selenium-config))
        {:keys [user-id api-token]} (create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id]} (project/create-project "test-check-allow-answers")]
    (try
      (label/add-label-entry-boolean
       project-id {:name "include" :question "include?" :short-label "Include"
                   :inclusion-value true :required true})
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345 12346]}
                                  :url url)]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (let [article-id (q/find-one [:article :a] {:ad.external-id (db/to-jsonb "12345")}
                                   :article-id, :join [:article-data:ad :a.article-data-id])
            label-id (q/find-one :label {:project-id project-id} :label-id)]
        (is (s/valid? ::sc/article-id article-id))
        (is (s/valid? ::sc/label-id label-id))
        (answer/set-user-article-labels
         user-id article-id {label-id true}
         :imported? false :change? false :confirm? true :resolve? false)
        (let [response (webapi-post "import-pmids" {:api-token api-token
                                                    :project-id project-id
                                                    :pmids [12347]}
                                    :url url)]
          (is (contains? response :error))
          (is (not (-> response :result :success)))))
      (finally
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-clone-project
  (let [source-project-name "test-clone-project"
        dest-project-name (str "[cloned] " source-project-name)
        {:keys [url]} (get-selenium-config)
        {:keys [user-id api-token]} (create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        source-project-id (-> (webapi-post "create-project"
                                           {:api-token api-token
                                            :project-name source-project-name
                                            :add-self? true}
                                           :url url)
                              :result :project :project-id)
        _ (is (integer? source-project-id))]
    (try
      (let [n-articles (-> (webapi-post "import-pmids"
                                        {:api-token api-token
                                         :project-id source-project-id
                                         :pmids [12345 12346]}
                                        :url url)
                           :result :project-articles)
            _ (is (= 2 n-articles))
            response (webapi-post "clone-project"
                                  {:api-token api-token
                                   :project-id source-project-id
                                   :new-project-name dest-project-name
                                   :articles true
                                   :labels true
                                   :answers true
                                   :members true
                                   :user-ids-only [user-id]}
                                  :url url)
            {:keys [success new-project]} (:result response)
            dest-id (:project-id new-project)]
        (is (true? success))
        (is (integer? dest-id))
        (is (= dest-project-name (:name new-project)))
        (is (= n-articles (project/project-article-count dest-id)))
        (is (= 1 (count (label/project-members-info dest-id))))
        (is (= 0 (count (label/query-public-article-labels dest-id))))
        (when dest-id (project/delete-project dest-id)))
      (finally
        (when user-id (user/delete-user user-id))
        (when source-project-id (project/delete-project source-project-id))))))
