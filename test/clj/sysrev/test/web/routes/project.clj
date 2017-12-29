(ns sysrev.test.web.routes.project
  (:require [clojure.test :refer :all]
            [sysrev.db.users :refer [create-user]]
            [sysrev.web.core :refer [wrap-sysrev-app
                                     load-app-routes]]
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
  request to add the headers required by the wrap-sysrev-app handler"
  [ring-session csrf-token]
  (fn [request]
    (-> request
        (mock/header "x-csrf-token" csrf-token)
        (mock/header "Cookie" (str "ring-session=" ring-session))
        (mock/header "Content-Type" "application/transit+json"))))

(deftest pubmed-search-test
  (let [handler (wrap-sysrev-app (load-app-routes) false)
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
    (create-user email password :project-id 100)
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