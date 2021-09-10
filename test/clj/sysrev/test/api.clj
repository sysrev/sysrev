(ns sysrev.test.api
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is use-fixtures]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.test.core :refer [default-fixture get-selenium-config]]
            [sysrev.test.browser.core :as b]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.user.core :as user]
            [sysrev.project.core :as project]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            sysrev.web.routes.api.handlers
            [sysrev.util :refer [in?]]))

(use-fixtures :once default-fixture)

;; HTTP client functions for testing API handlers
(defn webapi-request [method route body & {:keys [url]}]
  (let [url (or url (:url (get-selenium-config)))
        request (cond-> {:method method
                         :url (format "%sweb-api/%s" url route)
                         :content-type "application/json"
                         :as :application/json
                         :throw-exceptions false}
                  (= method :get)   (assoc :query-params body)
                  (= method :post)  (assoc :body (json/write-str body)))
        {:keys [body]} (http/request request)]
    (try (json/read-str body :key-fn keyword)
         (catch Throwable _ body))))

(defn webapi-get [route body & opts]
  (apply webapi-request :get route body opts))

(defn webapi-post [route body & opts]
  (apply webapi-request :post route body opts))

(deftest test-get-api-token
  (let [{:keys [user-id email]} (b/create-test-user)]
    (try
      (let [response (webapi-get "get-api-token"
                                 {:email email :password b/test-password})]
        (is (contains? response :result))
        (is (string? (-> response :result :api-token))))
      (finally
        (when user-id (user/delete-user user-id))))))

(deftest test-import-pmids
  (let [{:keys [user-id api-token]} (b/create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id]} (project/create-project "test-import-pmids")]
    (try
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345 12346]})]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (finally
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-import-article-text
  (let [{:keys [user-id api-token]} (b/create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id]} (project/create-project "test-import-article-text")]
    (try
      (let [response (webapi-post "import-article-text"
                                  {:api-token api-token
                                   :project-id project-id
                                   :articles [{:primary-title "article 1"
                                               :abstract "abstract text 1"}
                                              {:primary-title "abstract 2"
                                               :abstract "abstract text 2"}]})]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :attempted)))
        (is (= 2 (-> response :result :project-articles))))
      (finally
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-create-project
  (let [{:keys [user-id api-token]} (b/create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        project-name "test-create-project"]
    (try (let [response (webapi-post "create-project" {:api-token api-token
                                                       :project-name project-name
                                                       :add-self? true})
               {:keys [success project]} (:result response)
               {:keys [project-id name]} project]
           (try (is (true? success))
                (is (integer? project-id))
                (is (string? name))
                (is (= name project-name))
                (is (q/query-project-by-id project-id [:project-id]))
                (is (in? (->> (q/select-label-where project-id true [:name])
                              do-query (map :name))
                         "overall include"))
                (is (= (:name (q/query-project-by-id project-id [:name]))
                       project-name))
                (finally (some-> project-id (project/delete-project)))))
         (finally (some-> user-id (user/delete-user))))))

(deftest test-check-allow-answers
  (let [{:keys [user-id api-token]} (b/create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        {:keys [project-id]} (project/create-project "test-check-allow-answers")]
    (try
      (label/add-label-overall-include project-id)
      (let [response (webapi-post "import-pmids"
                                  {:api-token api-token
                                   :project-id project-id
                                   :pmids [12345 12346]})]
        (is (true? (-> response :result :success)))
        (is (= 2 (-> response :result :project-articles))))
      (let [article-id (q/find-one [:article :a] {:a.project-id project-id
                                                  :ad.external-id (db/to-jsonb "12345")}
                                   :article-id, :join [[:article-data :ad] :a.article-data-id])
            label-id (q/find-one :label {:project-id project-id} :label-id)]
        (is (s/valid? ::sc/article-id article-id))
        (is (s/valid? ::sc/label-id label-id))
        (answer/set-user-article-labels
         user-id article-id {label-id true}
         :imported? false :change? false :confirm? true :resolve? false)
        (let [response (webapi-post "import-pmids" {:api-token api-token
                                                    :project-id project-id
                                                    :pmids [12347]})]
          (is (contains? response :error))
          (is (not (-> response :result :success)))))
      (finally
        (when user-id (user/delete-user user-id))
        (when project-id (project/delete-project project-id))))))

(deftest test-clone-project
  (let [source-project-name "test-clone-project"
        dest-project-name (str "[cloned] " source-project-name)
        {:keys [user-id api-token]} (b/create-test-user)
        _ (user/set-user-permissions user-id ["user" "admin"])
        source-project-id (-> (webapi-post "create-project"
                                           {:api-token api-token
                                            :project-name source-project-name
                                            :add-self? true})
                              :result :project :project-id)
        _ (is (integer? source-project-id))]
    (try
      (let [n-articles (-> (webapi-post "import-pmids"
                                        {:api-token api-token
                                         :project-id source-project-id
                                         :pmids [12345 12346]})
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
                                   :user-ids-only [user-id]})
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
