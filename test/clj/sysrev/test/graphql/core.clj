(ns sysrev.test.graphql.core
  (:require [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [sysrev.datasource.api :refer [ds-auth-key]]
            [sysrev.web.core :refer [sysrev-handler]]))

(defonce api-key (atom nil))

(defn graphql-request
  "Make a request on app, returning the body as a clojure map"
  [query & {:keys [api-key] :or {api-key @api-key}}]
  (let [app (sysrev-handler)
        body (-> (app (-> (mock/request :post "/graphql")
                          (mock/header "Authorization" (str "Bearer " api-key))
                          (mock/json-body {:query query})))
                 :body)]
    (try (json/read-str body :key-fn keyword)
         (catch Exception _
           body))))

(defn graphql-fixture
  "Use the sysrev auth-key for tests that will also run against datasource. Set the api key for the test user to the same value"
  [f]
  (reset! api-key (ds-auth-key))
  (f)
  (reset! api-key nil))
