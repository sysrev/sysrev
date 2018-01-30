(ns sysrev.test.web.routes.project
  (:require [clojure.test :refer :all]
            [sysrev.db.project :as project]
            [sysrev.db.sources :as sources]
            [sysrev.db.users :as users]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [sysrev.test.web.routes.utils :refer [route-response-fn]]
            [sysrev.import.pubmed :as pubmed]
            [ring.mock.request :as mock]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest pubmed-search-test
  (let [handler (sysrev-handler)
        email "foo@bar.com"
        password "foobar"
        route-response (route-response-fn handler)]
    ;; create user
    (users/create-user email password :project-id 100)
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
                               {:pmids
                                (clojure.string/join
                                 ","
                                 (:pmids (pubmed/get-search-query-response "foo bar" 1)))})
               :result (get 25706626) :authors first)
           {:name "Aung T", :authtype "Author", :clusterid ""}))))

(deftest create-project-test
  (let [handler (sysrev-handler)
        email "foo@bar.com"
        password "foobar"
        search-term "foo bar"
        route-response (route-response-fn handler)]
    ;; create user
    (users/create-user email password :project-id 100)
    ;; login this user
    (is (get-in (route-response :post "/api/auth/login"
                                {:email email :password password})
                [:result :valid]))
    ;; Create a project
    (let [create-project-response
          (route-response :post "/api/create-project"
                          {:project-name "Foo's Bar"})
          new-project-id (get-in create-project-response [:result :project :project-id])
          search-query-result (pubmed/get-search-query-response search-term 1)
          meta (sources/import-pmids-search-term-meta search-term (count (:pmids search-query-result)))]
      ;; create a project for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0
             (project/project-article-count new-project-id)))
      ;; add articles to this project
      (pubmed/import-pmids-to-project-with-meta!
       (:pmids search-query-result) new-project-id meta)
      ;; Does the new project have the correct amount of articles?
      ;; I would like a 'get-project' route
      ;;
      ;; deletion can't happen for a user who isn't part of the project
      (let [non-member-email "non@member.com"
            non-member-password "nonmember"
            {:keys [user-id]} (users/create-user non-member-email non-member-password)]
        (route-response :post "/api/auth/login"
                        {:email non-member-email :password non-member-password})
        (is (= "Not authorized (project)"
               (get-in (route-response :post "/api/delete-project"
                                       {:project-id new-project-id})
                       [:error :message])))
        ;; deletion can't happen for a user who isn't an admin of the project
        (project/add-project-member new-project-id user-id)
        (is (= "Not authorized (project)"
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
        email "foo@bar.com"
        password "foobar"
        ;; create user
        {:keys [user-id]} (users/create-user email password)
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
                          {:project-name "The taming of the foo"})]
      (is (true? (-> create-response :result :success))))
    (let [projects (:projects (users/user-self-info user-id))]
      (is (= 1 (count projects)))
      (let [project-id (-> projects first :project-id)]
        (is (integer? project-id))
        (let [select-response
              (route-response :post "/api/select-project"
                              {:project-id project-id})]
          (is (= (-> select-response :result :project-id) project-id)
              (format "response = %s" (pr-str select-response))))))

    ;; the projects array in identity contains one entry
    (let [response (route-response :get "/api/auth/identity")]
      (is (= 1 (-> response :result :projects count))
          (format "response = %s" (pr-str response))))))

(deftest add-articles-from-pubmed-search-test
  (let [handler (sysrev-handler)
        email "foo@bar.com"
        password "foobar"
        search-term "foo bar"
        route-response (route-response-fn handler)]
    ;; create user
    (users/create-user email password :project-id 100)
    ;; login this user
    (is (get-in (route-response :post "/api/auth/login"
                                {:email email :password password})
                [:result :valid]))
    ;; Create a project
    (let [create-project-response
          (route-response :post "/api/create-project"
                          {:project-name "The taming of the foo"})
          new-project-id (get-in create-project-response [:result :project :project-id])]
      ;; select this project as the current project
      (is (= new-project-id
             (get-in (route-response :post "/api/select-project"
                                     {:project-id new-project-id})
                     [:result :project-id])))
      ;; confirm project is created for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0
             (project/project-article-count new-project-id)))
      ;; these should be no sources for this project yet
      (let [response (route-response :get "/api/project-sources")]
        (is (empty? (get-in response
                            [:result :sources]))))
      ;; add a member to a project
      (let [new-user-email "baz@qux.com"
            new-user-password "bazqux"]
        ;; create and log the new member in
        (users/create-user new-user-email new-user-password)
        ;; login this user
        (is (get-in (route-response :post "/api/auth/login"
                                    {:email new-user-email :password new-user-password})
                    [:result :valid]))
        ;; add member to project
        (is (= new-project-id
               (get-in (route-response :post "/api/join-project"
                                       {:project-id new-project-id})
                       [:result :project-id])))
        ;; that member can't add articles to a project
        (is (= "Not authorized"
               (get-in (route-response :post "/api/import-articles-from-search"
                                       {:search-term search-term :source "PubMed"})
                       [:error :message]))))
      ;; log back in as admin
      (is (get-in (route-response :post "/api/auth/login"
                                  {:email email :password password})
                  [:result :valid]))
      ;; user can add articles from a short search
      (is (get-in (route-response :post "/api/import-articles-from-search"
                                  {:search-term search-term :source "PubMed"})
                  [:result :success]))
      ;; meta data looks right
      (is (= 1
             (count
              (filter #(= (:project-id %) new-project-id)
                      (get-in (route-response :get "/api/project-sources")
                              [:result :sources])))))
      ;; repeat search, check to see that the import is not happening over and over
      (dotimes [n 10]
        (route-response :post "/api/import-articles-from-search"
                        {:search-term search-term :source "PubMed"}))
      ;; sources would be added multiple times if the same import was being run
      ;; if only one occurs, the count should be 1
      (is (= 1
             (count
              (filter #(= (:project-id %) new-project-id)
                      (get-in (route-response :get "/api/project-sources")
                              [:result :sources])))))
      ;; let's do another search, multiple times and see that only one import occurred
      (dotimes [n 10]
        (route-response :post "/api/import-articles-from-search"
                        {:search-term "grault" :source "PubMed"}))
      (is (= 2
             (count (filter #(= (:project-id %) new-project-id)
                            (get-in (route-response :get "/api/project-sources")
                                    [:result :sources]))))))))

(deftest delete-project-and-sources
  (let [handler (sysrev-handler)
        email "foo@bar.com"
        password "foobar"
        route-response (route-response-fn handler)
        _ (users/create-user email password)
        _ (route-response :post "/api/auth/login"
                          {:email email :password password})
        create-project-response (route-response :post "/api/create-project"
                                                {:project-name "Foo's Bar"})
        _ (route-response :post "/api/select-project"
                          {:project-id (get-in create-project-response [:result :project :project-id])})
        new-project-id (get-in create-project-response [:result :project :project-id])
        ;; add articles to the project
        import-articles-response (route-response :post "/api/import-articles-from-search"
                                                 {:search-term "foo bar" :source "PubMed"})
        project-info (route-response :get "/api/project-info")
        project-label (second (first (get-in project-info [:result :project :labels])))
        label-id (get-in project-label [:label-id])
        article-to-label (route-response :get "/api/label-task")
        article-id (get-in article-to-label [:result :article :article-id])
        project-sources-response (route-response :get "/api/project-sources")
        foo-bar-search-source (first (get-in project-sources-response [:result :sources]))
        foo-bar-search-source-id (:source-id foo-bar-search-source)]
    ;; the project does not have labeled articles, this should not
    ;; be true
    (is (not (project/project-has-labeled-articles? new-project-id)))
    ;; set an article label to true
    (route-response :post "/api/set-labels"
                    {:article-id article-id
                     :label-values {label-id true}
                     :confirm? true
                     :resolve? false
                     :change? false})
    ;; now the project has labeled articles
    (is (project/project-has-labeled-articles? new-project-id))
    (is (get-in (route-response :post "/api/delete-project"
                                {:project-id new-project-id})
                [:result :success]))
    ;; the project source has labeled articles as well
    (is (sources/source-has-labeled-articles? foo-bar-search-source-id))
    ;; the project source can not be deleted
    (is (= "Source contains reviewed articles"
           (get-in (route-response :post "/api/delete-source"
                                   {:source-id foo-bar-search-source-id})
                   [:error :message])))
    (let [import-articles-response (route-response :post "/api/import-articles-from-search"
                                                   {:search-term "grault" :source "PubMed"})
          project-sources-response (route-response :get "/api/project-sources")
          grault-search-source (first (filter #(= (get-in % [:meta :search-term]) "grault")
                                              (get-in project-sources-response [:result :sources])))
          grault-search-source-id (:source-id grault-search-source)]
      ;; are the total project articles equivalent to the sum of its two sources?
      (is (= (+ (:article-count grault-search-source) (:article-count foo-bar-search-source))
             (project/project-article-count new-project-id)))
      ;; try it another way
      (is (= (reduce + (map #(:article-count %)
                            (get-in (route-response :get "/api/project-sources")
                                    [:result :sources])))
             (project/project-article-count new-project-id)))
      ;; can grault-search-source be deleted?
      (is (get-in (route-response :post "/api/delete-source"
                                  {:source-id grault-search-source-id})
                  [:result :success]))
      ;; are the total articles equivalent to sum of its single source?
      (is (= (reduce + (map #(:article-count %)
                            (get-in (route-response :get "/api/project-sources")
                                    [:result :sources])))
             (project/project-article-count new-project-id))))))
