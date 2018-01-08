(ns sysrev.test.web.routes.utils
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [sysrev.util :as util]))

;; from https://gist.github.com/cyppan/864c09c479d1f0902da5
(defn parse-cookies
  "Given a Cookie header string, parse it into a map"
  [cookie-string]
  (when cookie-string
    (into {}
          (for [cookie (.split cookie-string ";")]
            (let [keyval (map #(.trim %) (.split cookie "=" 2))]
              [(keyword (first keyval)) (second keyval)])))))

(defn required-headers-params
  "Given a handler, return a map containing ring-session and csrf-token"
  [handler]
  (let [identity (handler
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
    {:ring-session ring-session
     :csrf-token csrf-token}))

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


