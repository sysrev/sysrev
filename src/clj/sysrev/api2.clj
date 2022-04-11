(ns sysrev.api2
  (:require
   [io.pedestal.test]
   [sysrev.json.interface :as json]))

(defn response-for [sr-context verb url & options]
  (apply
   io.pedestal.test/response-for (get-in sr-context [:sysrev-api-pedestal :service :io.pedestal.http/service-fn])
   verb url options))

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute! [sr-context query & [variables & {:keys [api-token]}]]
  (-> (response-for sr-context
                    :post "/api"
                    :headers (cond-> {"Content-Type" "application/json"}
                               api-token (assoc "Authorization" (str "Bearer " api-token)))
                    :body (cond-> {:query query}
                            (seq variables) (assoc :variables variables)
                            true json/write-str))
      (update :body json/read-str :key-fn keyword)))

(def ex! (comp throw-errors execute!))
