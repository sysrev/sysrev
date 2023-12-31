(ns sysrev.web.routes.project-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [medley.core :as medley :refer [find-first]]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member :refer [add-project-member
                                                      set-member-permissions]]
            [sysrev.source.core :as source]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test :refer [route-response-fn sysrev-handler]]
            [sysrev.test.e2e.core :as e]
            [sysrev.user.core :as user]
            [sysrev.util :as util :refer [sum]]))

(def test-project-name "Sysrev-Browser-Test")

(defn wait-for-project-import [route-response project-id n-sources]
  (Thread/sleep 2000)
  (test/wait-until #(let [{:keys [sources]}
                          (:result (route-response :get "/api/project-sources"
                                                   {:project-id project-id}))]
                      (and (= n-sources (count sources))
                           (->> sources
                                (map (partial get-in [:meta :importing-articles?]))
                                (every? (comp not true?)))))
                   15000 1000)
  (test/wait-until #(->> (route-response :get "/api/label-task" {:project-id project-id})
                         :result map?)
                   15000 1000)
  (Thread/sleep 500))

(deftest ^:optional pubmed-search-test
  (test/with-test-system [{:as system :keys [sr-context]} {}]
    (let [handler (sysrev-handler system)
          route-response (route-response-fn handler)
          {:keys [email password]} (test/create-test-user system)]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login"
                                  {:email email :password password})
                  [:result :valid]))
      ;; the user can search pubmed from sysrev
      (is (= (-> (pubmed/get-search-query-response sr-context "foo bar" 1)
                 :pmids count)
             (-> (route-response :get "/api/pubmed/search"
                                 {:term "foo bar"
                                  :page-number 1})
                 :result :pmids count)))
      ;; the user can get article summaries from pubmed
      (is (= {:name "Aung T", :authtype "Author", :clusterid ""}
             (-> (route-response :get "/api/pubmed/summaries"
                                 {:pmids (->> (pubmed/get-search-query-response sr-context "foo bar" 1)
                                              :pmids (str/join ","))})
                 :result (get 25706626) :authors first))))))

(deftest ^:integration create-project-test
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [handler (sysrev-handler system)
          search-term "foo bar"
          route-response (route-response-fn handler)
          {:keys [email password]} (test/create-test-user system)
          test-project-name (str test-project-name "-" (util/random-id))]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login" {:email email :password password})
                  [:result :valid]))
      ;; Create a project
      (let [create-project-response (route-response :post "/api/create-project"
                                                    {:project-name test-project-name
                                                     :public-access true})
            new-project-id (get-in create-project-response [:result :project :project-id])]
        ;; create a project for this user
        (is (get-in create-project-response [:result :success]))
        ;; get the article count, should be 0
        (is (= 0 (project/project-article-count new-project-id)))
        ;; add articles to this project
        (src/import-source
         sr-context :pubmed new-project-id {:search-term search-term}
         {:use-future? false :threads 1})
        ;; Does the new project have the correct amount of articles?
        ;; I would like a 'get-project' route
        ;;
        ;; deletion can't happen for a user who isn't part of the project
        (let [non-member (test/create-test-user system :email "non@member.com"
                                                :password "nonmember")]
          (route-response :post "/api/auth/login" {:email (:email non-member)
                                                   :password (:password non-member)})
          (is (= "Not authorized (project member)"
                 (get-in (route-response :post "/api/disable-project"
                                         {:project-id new-project-id})
                         [:error :message])))
          ;; deletion can't happen for a user who isn't an admin of the project
          (add-project-member new-project-id (:user-id non-member))
          (is (= "Not authorized (project member)"
                 (get-in (route-response :post "/api/disable-project"
                                         {:project-id new-project-id})
                         [:error :message])))
          ;; add the user as an admin, they can now delete the project
          (set-member-permissions new-project-id (:user-id non-member) ["member" "admin"])
          (is (get-in (route-response :post "/api/disable-project"
                                      {:project-id new-project-id})
                      [:result :success])))))))

(deftest ^:integration identity-project-response-test
  (test/with-test-system [system {}]
    (let [handler (sysrev-handler system)
          route-response (route-response-fn handler)
          {:keys [email user-id password]} (test/create-test-user system)
          test-project-name (str test-project-name "-" (util/random-id))]
      (is (integer? user-id))
      (user/set-user-permissions user-id ["user"])
      ;; the projects array in identity is empty
      (route-response :post "/api/auth/login" {:email email :password password})
      (let [ident-response (route-response :get "/api/auth/identity")]
        (is (empty? (-> ident-response :result :projects))
            (format "response = %s" (pr-str ident-response)))
        (is (= user-id (-> ident-response :result :identity :user-id))
            (format "response = %s" (pr-str ident-response))))
      ;; create a new project
      (let [create-response (route-response :post "/api/create-project"
                                            {:project-name test-project-name :public-access true})]
        (is (true? (-> create-response :result :success))))
      (let [projects (->> (:projects (user/user-self-info user-id))
                          (filter :member?))]
        (is (= 1 (count projects)))
        (is (integer? (-> projects first :project-id))))
      ;; the projects array in identity contains one entry
      (let [response (route-response :get "/api/auth/identity")
            projects (->> response :result :projects (filter :member?))]
        (is (= 1 (count projects))
            (format "response = %s" (pr-str response)))))))

(deftest ^:optional add-articles-from-pubmed-search-test
  (test/with-test-system [system {}]
    (let [handler (sysrev-handler system)
          search-term "foo bar"
          route-response (route-response-fn handler)
          {:keys [email password]} (test/create-test-user system)
          new-user (test/create-test-user system :email "baz@qux.com" :password "bazqux")
          test-project-name (str test-project-name "-" (util/random-id))]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login"
                                  {:email email :password password})
                  [:result :valid]))
      ;; create a project
      (let [create-project-response (route-response :post "/api/create-project"
                                                    {:project-name test-project-name
                                                     :public-access true})
            project-id (get-in create-project-response [:result :project :project-id])]
        ;; confirm project is created for this user
        (is (get-in create-project-response [:result :success]))
        ;; get the article count, should be 0
        (is (= 0 (project/project-article-count project-id)))
        ;; these should be no sources for this project yet
        (let [response (route-response :get "/api/project-sources"
                                       {:project-id project-id})]
          (is (empty? (get-in response [:result :sources]))))
        ;; login this user
        (is (get-in (route-response :post "/api/auth/login"
                                    {:email (:email new-user)
                                     :password (:password new-user)})
                    [:result :valid]))
        ;; add member to project
        (member/add-project-member project-id (:user-id new-user))
        ;; that member can't add articles to a project
        (is (= "Not authorized (project member)"
               (get-in (route-response :post "/api/import-articles/pubmed"
                                       {:project-id project-id
                                        :search-term search-term :source "PubMed"})
                       [:error :message])))
        ;; log back in as admin
        (is (get-in (route-response :post "/api/auth/login"
                                    {:email email :password password})
                    [:result :valid]))
        ;; user can add articles from a short search
        (is (get-in (route-response :post "/api/import-articles/pubmed"
                                    {:project-id project-id
                                     :search-term search-term :source "PubMed"})
                    [:result :success]))
        (wait-for-project-import route-response project-id 1)
        ;; meta data looks right
        (is (= 1 (count (filter #(= (:project-id %) project-id)
                                (get-in (route-response :get "/api/project-sources"
                                                        {:project-id project-id})
                                        [:result :sources])))))
        ;; repeat search, check to see that the import is not happening over and over
        (dotimes [_ 10]
          (route-response :post "/api/import-articles/pubmed"
                          {:project-id project-id :search-term search-term :source "PubMed"}))
        (Thread/sleep 500)
        ;; sources would be added multiple times if the same import was being run
        ;; if only one occurs, the count should be 1
        (is (= 1 (count (filter #(= (:project-id %) project-id)
                                (get-in (route-response :get "/api/project-sources"
                                                        {:project-id project-id})
                                        [:result :sources])))))
        ;; let's do another search, multiple times and see that only one import occurred
        (dotimes [_ 10]
          (route-response :post "/api/import-articles/pubmed"
                          {:project-id project-id :search-term "grault" :source "PubMed"}))
        (Thread/sleep 500)
        (is (= 2 (count (filter #(= (:project-id %) project-id)
                                (get-in (route-response :get "/api/project-sources"
                                                        {:project-id project-id})
                                        [:result :sources])))))))))

(deftest ^:optional delete-project-sources
  (test/with-test-system [{:as system :keys [sr-context]} {}]
    (let [handler (sysrev-handler system)
          {:keys [email password]} (test/create-test-user system)
          route-response (route-response-fn handler)
          test-project-name (str test-project-name "-" (util/random-id))
          _ (route-response :post "/api/auth/login" {:email email :password password})
          create-project-response (route-response :post "/api/create-project"
                                                  {:project-name test-project-name
                                                   :public-access true})
          project-id (get-in create-project-response [:result :project :project-id])
          get-pubmed-source (fn [search-term]
                              (-> (route-response :get "/api/project-sources"
                                                  {:project-id project-id})
                                  (get-in [:result :sources])
                                  (->> (find-first #(= (get-in % [:meta :search-term])
                                                       search-term)))))
          ;; add articles to the project
          _ (route-response :post "/api/import-articles/pubmed"
                            {:project-id project-id
                             :search-term "foo bar" :source "PubMed"})
          _ (wait-for-project-import route-response project-id 1)
          _ (e/is-soon nil
                       (= (count (pubmed/get-all-pmids-for-query sr-context "foo bar"))
                          (:article-count (get-pubmed-source "foo bar")))
                       20000 2000)
          foo-bar-search-source (get-pubmed-source "foo bar")
          foo-bar-search-source-id (:source-id foo-bar-search-source)
          project-info (route-response :get "/api/project-info" {:project-id project-id})
          project-label (second (first (get-in project-info [:result :project :labels])))
          label-id (get-in project-label [:label-id])
          article-to-label (route-response :get "/api/label-task"
                                           {:project-id project-id})
          _ (is (contains? article-to-label :result))
          _ (is (map? (:result article-to-label)))
          _ (is (contains? (:result article-to-label) :article))
          article-id (get-in article-to-label [:result :article :article-id])]
      ;; the project does not have labeled articles, this should not
      ;; be true
      (is (not (project/project-has-labeled-articles? project-id)))
      ;; set an article label to true
      (route-response :post "/api/set-labels" {:project-id project-id
                                               :article-id article-id
                                               :label-values {label-id true}
                                               :confirm? true
                                               :resolve? false
                                               :change? false})
      ;; now the project has labeled articles
      (is (project/project-has-labeled-articles? project-id))
      ;; the project source has labeled articles as well
      (is (source/source-has-labeled-articles? foo-bar-search-source-id))
      ;; the project source can not be deleted
      (is (= "Source contains reviewed articles"
             (get-in (route-response :post "/api/delete-source"
                                     {:project-id project-id
                                      :source-id foo-bar-search-source-id})
                     [:error :message])))
      (let [_ (route-response :post "/api/import-articles/pubmed"
                              {:project-id project-id
                               :search-term "grault" :source "PubMed"})
            _ (wait-for-project-import route-response project-id 2)
            _ (e/is-soon nil
                         (= (count (pubmed/get-all-pmids-for-query sr-context "grault"))
                            (:article-count (get-pubmed-source "grault")))
                         20000 2000)
            grault-search-source (get-pubmed-source "grault")
            grault-search-source-id (:source-id grault-search-source)]
        ;; are the total project articles equivalent to the sum of its two sources?
        (is (= (count (pubmed/get-all-pmids-for-query sr-context "grault"))
               (:article-count grault-search-source)))
        (e/is-soon nil
                   (= (project/project-article-count project-id)
                      (+ (:article-count foo-bar-search-source)
                         (:article-count grault-search-source)))
                   15000 1000)
        ;; try it another way
        (e/is-soon nil
                   (= (project/project-article-count project-id)
                      (sum (map :article-count
                                (get-in (route-response :get "/api/project-sources"
                                                        {:project-id project-id})
                                        [:result :sources]))))
                   15000 1000)
        ;; can grault-search-source be deleted?
        (is (get-in (route-response :post "/api/delete-source"
                                    {:project-id project-id
                                     :source-id grault-search-source-id})
                    [:result :success]))
        ;; are the total articles equivalent to sum of its single source?
        (e/is-soon nil
                   (= (project/project-article-count project-id)
                      (sum (map :article-count
                                (get-in (route-response :get "/api/project-sources"
                                                        {:project-id project-id})
                                        [:result :sources]))))
                   15000 1000)))))

(deftest ^:integration clone-user-project-test
  (test/with-test-system [system {}]
    (let [handler (sysrev-handler system)
          route-response (route-response-fn handler)
          test-user-a (test/create-test-user system)
          test-user-b (test/create-test-user system)
          project-name "Clone-SRC-Test"
          test-project-name (str project-name "-" (util/random-id))]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login" test-user-a)
                  [:result :valid]))
      ;; Create a project
      (let [create-project-response (route-response :post "/api/create-project"
                                                    {:project-name test-project-name :public-access true})
            new-project-id (get-in create-project-response [:result :project :project-id])]
        ;; create a project for this user
        (is (get-in create-project-response [:result :success]))
        ;; get the article count, should be 0
        (is (= 0 (project/project-article-count new-project-id)))
        ;; set this project to private
        (is (not (:public-access (project/change-project-setting new-project-id :public-access false))))
        ;; user can clone their project
        (is (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                    [:result :success]))
        ;;.. but another user can't clone it
        (is (get-in (route-response :post "/api/auth/login" test-user-b)
                    [:result :valid]))
        (is (= (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                       [:error :message])
               "You don't have permission to clone that project"))))))

(deftest ^:integration clone-org-project-test
  (test/with-test-system [system {}]
    (let [handler (sysrev-handler system)
          route-response (route-response-fn handler)
          test-user-a (test/create-test-user system)
          test-user-b (test/create-test-user system)
          project-name "Clone-SRC-Test"
          test-project-name (str project-name "-" (util/random-id))
          test-org "Alpha-Org"
          org-name (str test-org "-" (util/random-id))]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login" test-user-a)
                  [:result :valid]))
      ;; Create an org and project
      (let [org (route-response :post "/api/org" {:org-name org-name})
            new-org-id (get-in org [:result :id])
            create-project-response (route-response
                                     :post (str "/api/org/" new-org-id "/project")
                                     {:project-name test-project-name :public-access true})
            new-project-id (get-in create-project-response [:result :project :project-id])]
        ;; create a new-project
        (is (get-in create-project-response [:result :success]))
        ;; set this project to private
        (is (not (:public-access (project/change-project-setting new-project-id :public-access false))))
        ;; The org owner can clone this project
        (is (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                    [:result :success]))
        ;; but someone else can't
        (add-project-member new-project-id (:user-id test-user-b))
        (is (get-in (route-response :post "/api/auth/login" test-user-b)
                    [:result :valid]))
        (is (= (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                       [:error :message])
               "You don't have permission to clone that project"))
        ;; someone else also can't clone the project to their own group
        (let [test-org-b "Bravo-Org"
              org-name-b (str test-org-b "-" (util/random-id))
              org-b (route-response :post "/api/org" {:org-name org-name-b})
              org-b-id (get-in org-b [:result :id])]
          (is (= (get-in (route-response :post (str "/api/org/" org-b-id  "/project/clone")
                                         {:src-project-id new-project-id})
                         [:error :message])
                 "You don't have permission to clone that project")))))))
