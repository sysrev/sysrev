(ns sysrev.graphql.handler
  (:require [clojure.data.codec.base64 :as base64]
            [clojure.data.json :as json]
            [com.walmartlabs.lacinia :refer [execute]]))

(defn variable-map
  "Reads the `variables` query parameter, which contains a JSON string
  for any and all GraphQL variables to be associated with this request.
  Returns a map of the variables (using keyword keys)."
  [request]
  (let [variables (condp = (:request-method request)
                    :get (try (-> request
                                  (get-in [:query-params "variables"]))
                              (json/read-str :key-fn keyword)
                              (catch Exception _ nil))
                    :post (try (-> request
                                   :body
                                   (json/read-str :key-fn keyword)
                                   :variables)
                               (catch Exception _ nil)))]
    (if-not (empty? variables)
      variables
      {})))

(defn extract-query
  "Reads the `query` query parameters, which contains a JSON string
  for the GraphQL query associated with this request. Returns a
  string.  Note that this differs from the PersistentArrayMap returned
  by variable-map. e.g. The variable map is a hashmap whereas the
  query is still a plain string."
  [request]
  (case (:request-method request)
    :get  (get-in request [:query-params "query"])
    ;; Additional error handling because the clojure ring server still
    ;; hasn't handed over the values of the request to lacinia GraphQL
    :post (try (-> request
                   :body
                   slurp
                   (json/read-str :key-fn keyword)
                   :query)
               (catch Exception _ ""))
    :else ""))

;; https://github.com/remvee/ring-basic-authentication/blob/master/src/ring/middleware/basic_authentication.clj
(defn- byte-transform
  "Used to encode and decode strings.  Returns nil when an exception
  was raised."
  [direction-fn string]
  (try
    (apply str (map char (direction-fn (.getBytes string))))
    (catch Exception _)))

(defn decode-base64
  "Will do a base64 decoding of a string and return a string."
  [^String string]
  (byte-transform base64/decode string))

;; https://github.com/remvee/ring-basic-authentication/blob/master/src/ring/middleware/basic_authentication.clj
(defn extract-authorization-key
  "Extract the authorization key from the request header. The
  authorization header is of the form: Authorization: Basic <key>"
  [request]
  (let [auth-header (-> request
                        :headers
                        (get "authorization"))
        cred (and auth-header (decode-base64 (last (re-find #"^Basic (.*)$" auth-header))))
        [user pass] (and cred (clojure.string/split (str cred) #":" 2))]
    user))

(defn bearer-key
  [request]
  (let [auth (-> request :headers (get "authorization"))]
    (and auth (->> auth (re-find #"^Bearer (.*)$")
                   second))))

(defn get-authorization-key
  "Extract the authorization key from the request header. The
  authorization header is of the form: Authorization: bearer <key>"
  [request]
  (or (extract-authorization-key request)
      (bearer-key request)))

;; modified clojure.walk/keywordize-keys
(defn transform-keys-to-json
  "Recursively transforms all map keys with - to _"
  [m]
  (let [f (fn [[k v]] [(-> k symbol str (clojure.string/replace "-" "_") keyword) v])]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn graphql-handler
  "Accepts a GraphQL query via GET or POST, and executes the query.
  Returns the result as text/json."
  [compiled-schema]
  (fn [request]
    (let [vars (variable-map request)
          query (extract-query request)
          result (execute compiled-schema query vars {:authorization (get-authorization-key request)})
          status (if (-> result :errors seq)
                   400
                   200)]
      {:status status
       :headers {"Content-Type" "application/json"}
       :body (json/write-str (transform-keys-to-json result))})))
