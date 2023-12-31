(ns sysrev.graphql.handler
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia :refer [execute]]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
            [sysrev.article.graphql :refer [parse-set-label-input
                                            serialize-set-label-input set-labels!]]
            [sysrev.graphql.authorization :refer [authorized?]]
            [sysrev.graphql.token :as token]
            [sysrev.project.graphql :as project]
            [sysrev.reviewer-time.interface :as reviewer-time]
            [sysrev.source.datasource
             :refer
             [import-dataset import-datasource
              import-datasource-flattened
              import-ds-query]]
            [sysrev.source.project-filter :refer [import-article-filter-url!]]
            [sysrev.util :as util])
  (:import [java.util Base64]))

(def scalars
  {:Timestamp {:parse (fn [x]
                        (if (and (nat-int? x) (<= x Long/MAX_VALUE))
                          x
                          (throw (ex-info "Must be a non-negative long representing Unix epoch time."
                                          {:value x}))))
               :serialize identity}
   :SetLabelInput
   {:parse parse-set-label-input
    :serialize serialize-set-label-input}})

(defn compile-sysrev-schema []
  (-> (io/resource "edn/graphql-schema.edn")
      slurp
      edn/read-string
      (attach-resolvers {:Project/reviewerTime #'reviewer-time/Project-reviewerTime
                         :getTokenInfo #'token/getTokenInfo
                         :resolve-project #'project/project
                         :resolve-import-articles! #'import-ds-query
                         :resolve-import-dataset! #'import-dataset
                         :resolve-import-datasource! #'import-datasource
                         :resolve-import-datasource-flattened! #'import-datasource-flattened
                         :resolve-import-article-filter-url! #'import-article-filter-url!
                         :resolve-set-labels! #'set-labels!})
      (assoc :scalars scalars)
      (schema/compile {:default-field-resolver schema/hyphenating-default-field-resolver})))

(def sysrev-schema (atom (compile-sysrev-schema)))

(defn variable-map
  "Reads the `variables` query parameter, which contains a JSON string
  for any and all GraphQL variables to be associated with this request.
  Returns a map of the variables (using keyword keys)."
  [request]
  (let [variables (condp = (:request-method request)
                    :get (try (-> request
                                  (get-in [:query-params "variables"])
                                  (json/read-str :key-fn keyword))
                              (catch Exception _ nil))
                    :post (try (-> request
                                   :body
                                   (json/read-str :key-fn keyword)
                                   :variables)
                               (catch Exception _ nil)))]
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
    :post (try (-> body (json/read-str :key-fn keyword) :query)
               (catch Throwable _ ""))
    ""))

(defn decode-base64
  "Will do a base64 decoding of a string and return a string."
  [^String s]
  (util/ignore-exceptions
   (-> (Base64/getDecoder)
       (.decode (.getBytes s "UTF-8"))
       (String. "UTF-8"))))

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
  (fn [{:keys [body sr-context] :as request}]
    (let [request (assoc request :body (slurp body))
          vars (variable-map request)
          query (extract-query request)
          context {:authorization (get-authorization-key request)
                   :request request
                   :sr-context sr-context}
          authorization-result (authorized? query vars context)
          result (if (-> authorization-result :resolved-value :behavior (= :error))
                   {:errors [(-> authorization-result :resolved-value :data)]}
                   (execute compiled-schema query vars context))]
      {:status (if (seq (:errors result)) 400 200)
       :headers {"Content-Type" "application/json"}
       :body (json/write-str (transform-keys-to-json result))})))
