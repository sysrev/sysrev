(ns sysrev.sysrev-api.pedestal
  (:require
   [clojure.core.async :refer [chan]]
   [com.walmartlabs.lacinia.pedestal2 :as pedestal2]
   [io.pedestal.http :as http]
   [sysrev.lacinia-pedestal.interface :as slp]
   [sysrev.sysrev-api.graphql :as graphql]))

(defn allowed-origins [env]
  {:allowed-origins
   ({:dev (constantly true)
     :prod #{"https://sysrev.com" "https://staging.sysrev.com"}
     :staging #{"https://sysrev.com" "https://staging.sysrev.com"}
     :test (constantly true)}
    env)
   :creds true
   :max-age 86400})

(defn service-map [{:keys [env host port] :as opts} pedestal]
  (let [compiled-schema (graphql/load-schema)
        app-context {:opts opts :pedestal pedestal}
        json-error-interceptors [pedestal2/json-response-interceptor
                                 pedestal2/error-response-interceptor
                                 slp/error-logging-interceptor]
        routes (into #{["/api"
                        :options
                        json-error-interceptors
                        :route-name ::graphql-api-cors-preflight]
                       ["/api"
                        :post (slp/api-interceptors compiled-schema app-context)
                        :route-name ::graphql-api]
                       ["/health"
                        :get
                        (conj json-error-interceptors
                              (constantly {:status 200 :headers {} :body ""}))
                        :route-name ::health-check]
                       ["/ide"
                        :get
                        (conj json-error-interceptors
                              (slp/graphiql-ide-handler {:api-path "/"}))
                        :route-name ::graphiql-ide]}
                     (pedestal2/graphiql-asset-routes "/assets/graphiql"))]
    (-> {:env env
         :graphql-schema compiled-schema
         ::http/allowed-origins (allowed-origins env)
         ::http/host host
         ::http/join? false
         ::http/routes routes
         ::http/port port
         ::http/type :jetty}
        pedestal2/enable-graphiql
        (pedestal2/enable-subscriptions
         graphql/load-schema
         {:app-context app-context
          :subscription-interceptors
          #__ (slp/subscription-interceptors compiled-schema app-context)
          :values-chan-fn #(chan 10)}))))

(defn pedestal []
  (slp/pedestal service-map))
