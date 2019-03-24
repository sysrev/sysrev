(ns sysrev.test.web.routes.project
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [sysrev.db.project :as project]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]
            [sysrev.db.users :as users]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [sysrev.test.browser.core :refer [test-login create-test-user]]
            [sysrev.test.web.routes.utils :refer [route-response-fn]]
            [sysrev.pubmed :as pubmed]
            [ring.mock.request :as mock]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(def test-project-name "Sysrev Browser Test")

(deftest pubmed-search-test
  (let [handler (sysrev-handler)
        {:keys [email password]} test-login
        route-response (route-response-fn handler)]
    ;; create user
    (create-test-user)
    ;; login this user
    (is (get-in (route-response :post "/api/auth/login"
                                {:email email :password password})
                [:result :valid]))
    ;; the user can search pubmed from sysrev
    (is (= (-> (route-response :get "/api/pubmed/search"
                               {:term "foo bar"
                                :page-number 1})
               :result :pmids count)
           (-> (pubmed/get-search-query-response "foo bar" 1)
               :pmids count)))
    ;; the user can get article summaries from pubmed
    (is (= (-> (route-response :get "/api/pubmed/summaries"
                               {:pmids (->> (pubmed/get-search-query-response "foo bar" 1)
                                            :pmids (str/join ","))})
               :result (get 25706626) :authors first)
           {:name "Aung T", :authtype "Author", :clusterid ""}))))

(deftest create-project-test
  (let [handler (sysrev-handler)
        {:keys [email password]} test-login
        search-term "foo bar"
        route-response (route-response-fn handler)]
    ;; create user
    (create-test-user)
    ;; login this user
    (is (get-in (route-response :post "/api/auth/login"
                                {:email email :password password})
                [:result :valid]))
    ;; Create a project
    (let [create-project-response
          (route-response :post "/api/create-project"
                          {:project-name test-project-name})
          new-project-id (get-in create-project-response [:result :project :project-id])
          search-query-result (pubmed/get-search-query-response search-term 1)
          meta (source/make-source-meta
                :pubmed {:search-term search-term
                         :search-count (count (:pmids search-query-result))})]
      ;; create a project for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0
             (project/project-article-count new-project-id)))
      ;; add articles to this project
      (import/import-pubmed-search
       new-project-id {:search-term search-term}
       {:use-future? false :threads 1})
      ;; Does the new project have the correct amount of articles?
      ;; I would like a 'get-project' route
      ;;
      ;; deletion can't happen for a user who isn't part of the project
      (let [non-member-email "non@member.com"
            non-member-password "nonmember"
            {:keys [user-id]} (create-test-user
                               :email non-member-email
                               :password non-member-password)]
        (route-response :post "/api/auth/login"
                        {:email non-member-email :password non-member-password})
        (is (= "Not authorized (project member)"
               (get-in (route-response :post "/api/delete-project"
                                       {:project-id new-project-id})
                       [:error :message])))
        ;; deletion can't happen for a user who isn't an admin of the project
        (project/add-project-member new-project-id user-id)
        (is (= "Not authorized (project member)"
               (get-in (route-response :post "/api/delete-project"
                                       {:project-id new-project-id})
                       [:error :message])))
        ;; add the user as an admin, they can now delete the project
        (project/set-member-permissions new-project-id user-id ["member" "admin"])
        (is (get-in (route-response :post "/api/delete-project"
                                    {:project-id new-project-id})
                    [:result :success]))))))

(deftest identity-project-response-test
  (let [handler (sysrev-handler)
        {:keys [email password]} test-login
        ;; create user
        {:keys [user-id]} (create-test-user :project-id nil)
        _ (users/set-user-permissions user-id ["user"])
        route-response (route-response-fn handler)]
    (is (integer? user-id))
    ;; the projects array in identity is empty
    (route-response :post "/api/auth/login"
                    {:email email :password password})
    (let [ident-response (route-response :get "/api/auth/identity")]
      (is (-> ident-response :result :projects empty?)
          (format "response = %s" (pr-str ident-response)))
      (is (= (-> ident-response :result :identity :user-id) user-id)
          (format "response = %s" (pr-str ident-response))))
    ;; create a new project
    (let [create-response
          (route-response :post "/api/create-project"
                          {:project-name test-project-name})]
      (is (true? (-> create-response :result :success))))
    (let [projects (->> (:projects (users/user-self-info user-id))
                        (filter :member?))]
      (is (= 1 (count projects)))
      (let [project-id (-> projects first :project-id)]
        (is (integer? project-id))))

    ;; the projects array in identity contains one entry
    (let [response (route-response :get "/api/auth/identity")
          projects (->> response :result :projects
                        (filter :member?))]
      (is (= 1 (count projects))
          (format "response = %s" (pr-str response))))))

(deftest add-articles-from-pubmed-search-test
  (let [handler (sysrev-handler)
        {:keys [email password]} test-login
        search-term "foo bar"
        route-response (route-response-fn handler)]
    ;; create user
    (create-test-user)
    ;; login this user
    (is (get-in (route-response :post "/api/auth/login"
                                {:email email :password password})
                [:result :valid]))
    ;; Create a project
    (let [create-project-response
          (route-response :post "/api/create-project"
                          {:project-name test-project-name})
          project-id (get-in create-project-response [:result :project :project-id])]
      ;; confirm project is created for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0
             (project/project-article-count project-id)))
      ;; these should be no sources for this project yet
      (let [response (route-response :get "/api/project-sources"
                                     {:project-id project-id})]
        (is (empty? (get-in response
                            [:result :sources]))))
      ;; add a member to a project
      (let [new-user-email "baz@qux.com"
            new-user-password "bazqux"]
        ;; create and log the new member in
        (create-test-user :email new-user-email
                          :password new-user-password)
        ;; login this user
        (is (get-in (route-response :post "/api/auth/login"
                                    {:email new-user-email :password new-user-password})
                    [:result :valid]))
        ;; add member to project
        (is (= project-id
               (get-in (route-response :post "/api/join-project"
                                       {:project-id project-id})
                       [:result :project-id])))
        ;; that member can't add articles to a project
        (is (= "Not authorized (project member)"
               (get-in (route-response :post "/api/import-articles/pubmed"
                                       {:project-id project-id
                                        :search-term search-term :source "PubMed"})
                       [:error :message]))))
      ;; log back in as admin
      (is (get-in (route-response :post "/api/auth/login"
                                  {:email email :password password})
                  [:result :valid]))
      ;; user can add articles from a short search
      (is (get-in (route-response :post "/api/import-articles/pubmed"
                                  {:project-id project-id
                                   :search-term search-term :source "PubMed"})
                  [:result :success]))
      ;; meta data looks right
      (is (= 1
             (count
              (filter #(= (:project-id %) project-id)
                      (get-in (route-response :get "/api/project-sources"
                                              {:project-id project-id})
                              [:result :sources])))))
      ;; repeat search, check to see that the import is not happening over and over
      (dotimes [n 10]
        (route-response :post "/api/import-articles/pubmed"
                        {:project-id project-id
                         :search-term search-term :source "PubMed"}))
      ;; sources would be added multiple times if the same import was being run
      ;; if only one occurs, the count should be 1
      (is (= 1
             (count
              (filter #(= (:project-id %) project-id)
                      (get-in (route-response :get "/api/project-sources"
                                              {:project-id project-id})
                              [:result :sources])))))
      ;; let's do another search, multiple times and see that only one import occurred
      (dotimes [n 10]
        (route-response :post "/api/import-articles/pubmed"
                        {:project-id project-id
                         :search-term "grault" :source "PubMed"}))
      (is (= 2
             (count (filter #(= (:project-id %) project-id)
                            (get-in (route-response :get "/api/project-sources"
                                                    {:project-id project-id})
                                    [:result :sources]))))))))

(deftest delete-project-and-sources
  (let [handler (sysrev-handler)
        {:keys [email password]} test-login
        route-response (route-response-fn handler)
        _ (create-test-user)
        _ (route-response :post "/api/auth/login"
                          {:email email :password password})
        create-project-response (route-response :post "/api/create-project"
                                                {:project-name test-project-name})
        project-id (get-in create-project-response [:result :project :project-id])
        ;; add articles to the project
        import-articles-response (route-response :post "/api/import-articles/pubmed"
                                                 {:project-id project-id
                                                  :search-term "foo bar" :source "PubMed"})
        project-info (route-response :get "/api/project-info"
                                     {:project-id project-id})
        project-label (second (first (get-in project-info [:result :project :labels])))
        label-id (get-in project-label [:label-id])
        article-to-label (route-response :get "/api/label-task"
                                         {:project-id project-id})
        article-id (get-in article-to-label [:result :article :article-id])
        project-sources-response (route-response :get "/api/project-sources"
                                                 {:project-id project-id})
        foo-bar-search-source (first (get-in project-sources-response [:result :sources]))
        foo-bar-search-source-id (:source-id foo-bar-search-source)]
    ;; the project does not have labeled articles, this should not
    ;; be true
    (is (not (project/project-has-labeled-articles? project-id)))
    ;; set an article label to true
    (route-response :post "/api/set-labels"
                    {:project-id project-id
                     :article-id article-id
                     :label-values {label-id true}
                     :confirm? true
                     :resolve? false
                     :change? false})
    ;; now the project has labeled articles
    (is (project/project-has-labeled-articles? project-id))
    (is (get-in (route-response :post "/api/delete-project"
                                {:project-id project-id})
                [:result :success]))
    ;; the project source has labeled articles as well
    (is (source/source-has-labeled-articles? foo-bar-search-source-id))
    ;; the project source can not be deleted
    (is (= "Source contains reviewed articles"
           (get-in (route-response :post "/api/delete-source"
                                   {:project-id project-id
                                    :source-id foo-bar-search-source-id})
                   [:error :message])))
    (let [import-articles-response (route-response :post "/api/import-articles/pubmed"
                                                   {:project-id project-id
                                                    :search-term "grault" :source "PubMed"})
          project-sources-response (route-response :get "/api/project-sources"
                                                   {:project-id project-id})
          grault-search-source (first (filter #(= (get-in % [:meta :search-term]) "grault")
                                              (get-in project-sources-response [:result :sources])))
          grault-search-source-id (:source-id grault-search-source)]
      ;; are the total project articles equivalent to the sum of its two sources?
      (is (= (+ (:article-count grault-search-source) (:article-count foo-bar-search-source))
             (project/project-article-count project-id)))
      ;; try it another way
      (is (= (reduce + (map #(:article-count %)
                            (get-in (route-response :get "/api/project-sources"
                                                    {:project-id project-id})
                                    [:result :sources])))
             (project/project-article-count project-id)))
      ;; can grault-search-source be deleted?
      (is (get-in (route-response :post "/api/delete-source"
                                  {:project-id project-id
                                   :source-id grault-search-source-id})
                  [:result :success]))
      ;; are the total articles equivalent to sum of its single source?
      (is (= (reduce + (map #(:article-count %)
                            (get-in (route-response :get "/api/project-sources"
                                                    {:project-id project-id})
                                    [:result :sources])))
             (project/project-article-count project-id))))))
