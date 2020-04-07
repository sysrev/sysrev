(ns sysrev.graphql.handler
  (:require [clojure.string :as str]
            [clojure.data.codec.base64 :as base64]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia :refer [execute]]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
            [sysrev.source.datasource :refer
             [import-ds-query import-dataset import-datasource import-datasource-flattened]]
            [sysrev.source.project-filter :refer [import-article-filter-url!]]
            [sysrev.project.graphql :as project]
            [sysrev.util :as util]))

(defn sysrev-schema []
  (-> (io/resource "edn/graphql-schema.edn")
      slurp
      edn/read-string
      (attach-resolvers {:resolve-project project/project
                         :resolve-import-articles! import-ds-query
                         :resolve-import-dataset! import-dataset
                         :resolve-import-datasource! import-datasource
                         :resolve-import-datasource-flattened! import-datasource-flattened
                         :resolve-import-article-filter-url! import-article-filter-url!})
      (schema/compile {:default-field-resolver schema/hyphenating-default-field-resolver})))

(defn variable-map
  "Reads the `variables` query parameter, which contains a JSON string
  for any and all GraphQL variables to be associated with this request.
  Returns a map of the variables (using keyword keys)."
  [request]
  (let [variables (condp = (:request-method request)
                    :get (try (-> request
                                  (get-in [:query-params "variables"]))
                              (json/read-str :key-fn keyword)
                              (catch Throwable _ nil))
                    :post (try (-> request
                                   :body
                                   (json/read-str :key-fn keyword)
                                   :variables)
                               (catch Throwable _ nil)))]
    (or (not-empty variables) {})))

(defn extract-query
  "Reads the `query` query parameters, which contains a JSON string
  for the GraphQL query associated with this request. Returns a
  string.  Note that this differs from the PersistentArrayMap returned
  by variable-map. e.g. The variable map is a hashmap whereas the
  query is still a plain string."
  [{:keys [body request-method query-params] :as _request}]
  (case request-method
    :get  (get query-params "query")
    ;; Additional error handling because the clojure ring server still
    ;; hasn't handed over the values of the request to lacinia GraphQL
    :post (try (-> (slurp body) (json/read-str :key-fn keyword) :query)
               (catch Throwable _ ""))
    :else ""))

;; https://github.com/remvee/ring-basic-authentication/blob/master/src/ring/middleware/basic_authentication.clj
(defn- byte-transform
  "Used to encode and decode strings.  Returns nil when an exception
  was raised."
  [direction-fn string]
  (util/ignore-exceptions
   (apply str (map char (direction-fn (.getBytes string))))))

(defn decode-base64
  "Will do a base64 decoding of a string and return a string."
  [^String string]
  (byte-transform base64/decode string))

;; https://github.com/remvee/ring-basic-authentication/blob/master/src/ring/middleware/basic_authentication.clj
(defn extract-authorization-key
  "Extract the authorization key from the request header. The
  authorization header is of the form: Authorization: Basic <key>"
  [{:keys [headers] :as _request}]
  (let [auth-header (get headers "authorization")
        cred (and auth-header (decode-base64 (last (re-find #"^Basic (.*)$" auth-header))))
        [user _pass] (and cred (str/split (str cred) #":" 2))]
    user))

(defn bearer-key [{:keys [headers] :as _request}]
  (second (some->> (get headers "authorization")
                   (re-find #"^Bearer (.*)$"))))

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
  (let [f (fn [[k v]] [(-> k symbol str (str/replace "-" "_") keyword) v])]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn graphql-handler
  "Accepts a GraphQL query via GET or POST, and executes the query.
  Returns the result as text/json."
  [compiled-schema]
  (fn [request]
    (let [vars (variable-map request)
          query (extract-query request)
          result (execute compiled-schema query vars
                          {:authorization (get-authorization-key request)})]
      {:status (if (seq (:errors result)) 400 200)
       :headers {"Content-Type" "application/json"}
       :body (json/write-str (transform-keys-to-json result))})))
