(ns sysrev.test.web.routes.project
  (:require [clojure.test :refer :all]
            [sysrev.web.core :refer [wrap-sysrev-app
                                     load-app-routes]]
            [sysrev.test.import.pubmed :as pubmed]
            [ring.mock.request :as mock]
            [sysrev.util :as util]))

(use-fixtures :each pubmed/clear)

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
    ;; login this user
    (is (-> (handler
             (->  (mock/request :post "/api/auth/login")
                  (mock/body (sysrev.util/write-transit-str
                              {:email "james@insilica.co" :password "test1234"}))
                  ((required-headers ring-session csrf-token))))
            :body util/read-transit-str :result :valid))
    ;; the user can search pubmed from sysrev
    (is (= (-> (handler
                (->  (mock/request :get "/api/pubmed/search")
                     (mock/query-string {:term "foo bar"})
                     ((required-headers ring-session csrf-token))))
               :body util/read-transit-str :result :pmids :esearchresult :idlist count)
           6))))
