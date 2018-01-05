(ns sysrev.test.web.routes.project
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
            [sysrev.import.pubmed :as pubmed]
            [ring.mock.request :as mock]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

;; from https://gist.github.com/cyppan/864c09c479d1f0902da5
(defn parse-cookies
  "Given a Cookie header string, parse it into a map"
  [cookie-string]
  (when cookie-string
    (into {}
          (for [cookie (.split cookie-string ";")]
            (let [keyval (map #(.trim %) (.split cookie "=" 2))]
              [(keyword (first keyval)) (second keyval)])))))

(defn required-headers
  "Given a ring-session and csrf-token str, return a fn that acts on a
  request to add the headers required by the handler"
  [ring-session csrf-token]
  (fn [request]
    (-> request
        (mock/header "x-csrf-token" csrf-token)
        (mock/header "Cookie" (str "ring-session=" ring-session))
        (mock/header "Content-Type" "application/transit+json"))))

(defn login-user
  "Given a ring-session, csrf-token, email and password, login user"
  [handler email password ring-session csrf-token]
  ;; login this user
  (is (-> (handler
           (->  (mock/request :post "/api/auth/login")
                (mock/body (sysrev.util/write-transit-str
                            {:email email :password password}))
                ((required-headers ring-session csrf-token))))
          :body util/read-transit-str :result :valid)))

(deftest pubmed-search-test
  (let [handler (sysrev-handler)
        ;; get the ring-session and csrf-token information
        identity (handler
                  (mock/request :get "/api/auth/identity"))
        email "foo@bar.com"
        password "foobar"
        ring-session (-> identity
                         :headers
                         (get "Set-Cookie")
                         first
                         parse-cookies
                         :ring-session)
        csrf-token (-> identity
                       :body
                       (util/read-transit-str)
                       :csrf-token)]
    ;; create user
    (users/create-user email password :project-id 100)
    ;; login this user
    (is (-> (handler
             (->  (mock/request :post "/api/auth/login")
                  (mock/body (sysrev.util/write-transit-str
                              {:email email :password password}))
                  ((required-headers ring-session csrf-token))))
            :body util/read-transit-str :result :valid))
    ;; the user can search pubmed from sysrev
    (is (= (-> (handler
                (->  (mock/request :get "/api/pubmed/search")
                     (mock/query-string {:term "foo bar"
                                         :page-number 1})
                     ((required-headers ring-session csrf-token))))
               :body util/read-transit-str :result :pmids count)
           (count (:pmids (pubmed/get-search-query-response "foo bar" 1)))))
    ;; the user can get article summaries from pubmed
    (is (= (-> (-> (handler
                    (-> (mock/request :get "/api/pubmed/summaries")
                        (mock/query-string
                         {:pmids
                          (clojure.string/join
                           ","
                           (:pmids (pubmed/get-search-query-response "foo bar" 1)))})
                        ((required-headers ring-session csrf-token))))
                   :body util/read-transit-str :result)
               (get 25706626)
               :authors
               first))
        {:name "Aung T", :authtype "Author", :clusterid ""})))

(deftest create-project-test
  (let [handler (sysrev-handler)
        email "foo@bar.com"
        password "foobar"
        ;; get the ring-session and csrf-token information
        identity (handler
                  (mock/request :get "/api/auth/identity"))
        ring-session (-> identity
                         :headers
                         (get "Set-Cookie")
                         first
                         parse-cookies
                         :ring-session)
        csrf-token (-> identity
                       :body
                       (util/read-transit-str)
                       :csrf-token)]
    ;; create user
    (users/create-user email password :project-id 100)
    ;; login this user
    (is (-> (handler
             (->  (mock/request :post "/api/auth/login")
                  (mock/body (sysrev.util/write-transit-str
                              {:email email :password password}))
                  ((required-headers ring-session csrf-token))))
            :body util/read-transit-str :result :valid))
    ;; Create a project
    (let [create-project-response
          (-> (handler
               (->  (mock/request :post "/api/create-project")
                    (mock/body (sysrev.util/write-transit-str
                                {:project-name "The taming of the foo"}))
                    ((required-headers ring-session csrf-token))))
              :body util/read-transit-str)
          new-project-id (get-in create-project-response [:result :project :project-id])
          search-query-result (pubmed/get-search-query-response "foo bar" 1)]
      ;; create a project for this user
      (is (get-in create-project-response [:result :success]))
      ;; get the article count, should be 0
      (is (= 0
             (project/project-article-count new-project-id)))
      ;; add articles to this project
      (pubmed/import-pmids-to-project (:pmids search-query-result) new-project-id)
      ;; Does the new project have the correct amount of articles?
      ;; I would like a 'get-project' route
      ;;
      ;; deletion can't happen for a user who isn't part of the project
      (let [non-member-email "non@member.com"
            non-member-password "nonmember"
            {:keys [user-id]} (users/create-user non-member-email non-member-password)]
        (login-user handler non-member-email non-member-password ring-session csrf-token)
        (is (= "Not authorized (project)"
               (-> (handler
                    (-> (mock/request :post "/api/delete-project")
                        (mock/body (sysrev.util/write-transit-str
                                    {:project-id new-project-id}))
                        ((required-headers ring-session csrf-token))))
                   :body
                   util/read-transit-str
                   (get-in [:error :message]))))
        ;; deletion can't happen for a user who isn't an admin of the project
        (project/add-project-member new-project-id user-id)
        (is (= "Not authorized (project)"
               (-> (handler
                    (-> (mock/request :post "/api/delete-project")
                        (mock/body (sysrev.util/write-transit-str
                                    {:project-id new-project-id}))
                        ((required-headers ring-session csrf-token))))
                   :body
                   util/read-transit-str
                   (get-in [:error :message]))))
        ;; add the user as an admin, they can now delete the project
        (project/set-member-permissions new-project-id user-id ["member" "admin"])
        (is (-> (handler
                 (-> (mock/request :post "/api/delete-project")
                     (mock/body (sysrev.util/write-transit-str
                                 {:project-id new-project-id}))
                     ((required-headers ring-session csrf-token))))
                :body
                util/read-transit-str
                (get-in [:result :success])))))))
