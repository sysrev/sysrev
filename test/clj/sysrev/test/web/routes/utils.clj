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
  "Given a handler, return a fn that acts on a
  request to add the headers required by the handler"
  [handler]
  (let [{:keys [ring-session csrf-token]}
        (required-headers-params handler)]
    (fn [request]
      (-> request
          (mock/header "x-csrf-token" csrf-token)
          (mock/header "Cookie" (str "ring-session=" ring-session))
          (mock/header "Content-Type" "application/transit+json")))))

(defn route-response-builder
  "Get the response from handler using request method at uri with optional
  map parameters. When method is :get, parameters are passing in query-string, when method is :post, parameters are passed in body as transit+json. Returns the body as a map"
  [handler required-headers-fn method uri & [parameters]]
  (let [request-params-fn (if (= :get method)
                            (fn [request]
                              (mock/query-string request parameters))
                            (fn [request]
                              (mock/body request (sysrev.util/write-transit-str
                                                  parameters))))]
    (-> (handler
         (->  (mock/request method uri)
              request-params-fn
              required-headers-fn))
        :body util/read-transit-str)))

(defn route-response-fn
  "Return a fn of method, uri and options parameters for handling a mock request given a handler and required-headers-fn"
  [handler]
  (let [required-headers-fn (required-headers handler)]
    (fn [method uri & [parameters]]
      (route-response-builder handler required-headers-fn method uri parameters))))


