(ns sysrev.sysrev-api.test
  (:require
   [cheshire.core :as json]
   [io.pedestal.test]))

(defn response-for [system verb url & options]
  (apply
   io.pedestal.test/response-for (get-in system [:sysrev-api-pedestal :service :io.pedestal.http/service-fn])
   verb url options))

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute! [system query & [variables {:keys [api-token]}]]
  (-> (response-for system
                    :post "/"
                    :headers (cond-> {"Content-Type" "application/json"}
                               api-token (assoc "Authorization" (str "Bearer " api-token)))
                    :body (json/generate-string {:query query
                                                 :variables variables}))
      (update :body json/parse-string true)))

