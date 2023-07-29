(ns datapub.pedestal
  (:require [clojure.core.async :refer [chan]]
            [com.walmartlabs.lacinia.pedestal2 :as pedestal2]
            [datapub.dataset :as dataset]
            [datapub.dataset.entity :as entity]
            [datapub.util :as util]
            [io.pedestal.http :as http]
            [sysrev.lacinia.interface :as sl]
            [sysrev.lacinia-pedestal.interface :as slp]
            [taoensso.timbre :as t]))

(def resolvers
  {:Dataset {:entities #'dataset/resolve-Dataset#entities
             :indices #'dataset/resolve-Dataset#indices}
   :DatasetEntitiesEdge {:node #'dataset/resolve-DatasetEntitiesEdge#node}
   :ListDatasetsEdge {:node #'dataset/resolve-ListDatasetsEdge#node}
   :Query {:dataset #'dataset/resolve-dataset
           :datasetEntitiesById #'entity/resolve-datasetEntitiesById
           :datasetEntity #'entity/resolve-dataset-entity
           :listDatasets #'dataset/list-datasets}
   :Mutation {:createDataset #'dataset/create-dataset!
              :createDatasetEntity #'entity/create-dataset-entity!
              :createDatasetIndex #'dataset/create-dataset-index!
              :updateDataset #'dataset/update-dataset!}
   :Subscription {:datasetEntities sl/resolve-value
                  :searchDataset sl/resolve-value}})

(def streamers
  {:Subscription {:datasetEntities #'entity/dataset-entities-subscription
                  :searchDataset #'dataset/search-dataset-subscription}})

(defn allowed-origins [env]
  {:allowed-origins
   ({:dev (constantly true)
     :prod #{"https://www.datapub.dev" "https://sysrev.com" "https://staging.sysrev.com"}
     :staging #{"https://www.datapub.dev" "https://sysrev.com" "https://staging.sysrev.com" "https://datapub.sysrevdev.net"}
     :test (constantly true)}
    env)
   :creds true
   :max-age 86400})

(defn throw-exception [context]
  (if (util/sysrev-dev? context)
    (let [message (str "Exception induced by developer: "
                       (get-in context [:request :params :message]))]
      (t/error message)
      (throw (Exception. message)))
    {:status 403 :headers {} :body "Forbidden"}))

(defn service-map [{:keys [env host port] :as opts} pedestal]
  (let [compiled-schema (sl/load-schema ["datapub/schema.graphql"
                                         "datapub/schema-subscription.graphql"]
                                        :resolvers resolvers
                                        :streamers streamers)
        app-context {:opts opts :pedestal pedestal}
        json-error-interceptors [pedestal2/json-response-interceptor
                                 pedestal2/error-response-interceptor
                                 slp/error-logging-interceptor]
        download-interceptors (conj json-error-interceptors
                                    #(entity/download-DatasetEntity-content
                                      (assoc app-context :request %)
                                      (allowed-origins env)))
        routes (into #{["/api"
                        :options
                        json-error-interceptors
                        :route-name ::graphql-api-cors-preflight]
                       ["/api"
                        :post (slp/api-interceptors compiled-schema app-context)
                        :route-name ::graphql-api]
                       ["/download/DatasetEntity/content/:entity-id/:content-hash"
                        :get download-interceptors
                        :route-name ::DatasetEntity-content]
                       ["/download/DatasetEntity/content/:entity-id/:content-hash"
                        :head download-interceptors
                        :route-name ::DatasetEntity-content-head]
                       ["/download/DatasetEntity/content/:entity-id/:content-hash"
                        :options
                        json-error-interceptors
                        :route-name ::DatasetEntity-content-cors-preflight]
                       ["/health"
                        :get
                        (conj json-error-interceptors
                              (constantly {:status 200 :headers {} :body ""}))
                        :route-name ::health-check]
                       ["/ide"
                        :get
                        (conj json-error-interceptors
                              (slp/graphiql-ide-handler {}))
                        :route-name ::graphiql-ide]
                       ["/throw-exception"
                        :post
                        (conj json-error-interceptors
                              #(throw-exception (assoc app-context :request %)))
                        :route-name ::throw-exception]}
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
         compiled-schema
         {:app-context app-context
          :subscription-interceptors
          #__ (slp/subscription-interceptors compiled-schema app-context)
          :values-chan-fn #(chan 10)}))))

(defn pedestal []
  (slp/pedestal service-map))
