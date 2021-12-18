(ns sysrev.test.graphql.core
  (:require [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [sysrev.datasource.api :refer [ds-auth-key]]
            [sysrev.test.core :refer [sysrev-handler]]
            [sysrev.util :as util]))

(defn graphql-request
  "Make a request on app, returning the body as a clojure map"
  [system query & {:keys [api-key] :or {api-key (ds-auth-key)}}]
  (let [app (sysrev-handler system)
        body (-> (app (-> (mock/request :post "/graphql")
                          (mock/header "Authorization" (str "Bearer " api-key))
                          (mock/json-body {:query (util/gquery query)})))
                 :body)]
    (try (json/read-str body :key-fn keyword)
         (catch Exception _
           body))))
