(ns datapub.graphql
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.parser.schema :as pschema]
            [com.walmartlabs.lacinia.schema :as schema]
            [datapub.dataset :as dataset]))

(def scalars
  {:NonNegativeInt {:parse (fn [x]
                             (if (nat-int? x)
                               x
                               (throw (ex-info "Must be a non-negative integer."
                                               {:value x}))))                    :serialize identity}
   :PositiveInt {:parse (fn [x]
                          (if (pos-int? x)
                            x
                            (throw (ex-info "Must be a positive integer."
                                            {:value x}))))                    :serialize identity}})

(defn resolve-value [_ _ value]
  value)

(def resolvers
  {:Dataset {:indices #'dataset/resolve-Dataset-indices}
   :ListDatasetsEdge {:node #'dataset/resolve-ListDatasetsEdge-node}
   :Query {:dataset #'dataset/resolve-dataset
           :datasetEntity #'dataset/resolve-dataset-entity
           :listDatasets #'dataset/list-datasets}
   :Mutation {:createDataset #'dataset/create-dataset!
              :createDatasetEntity #'dataset/create-dataset-entity!
              :createDatasetIndex #'dataset/create-dataset-index!}
   :Subscription {:datasetEntities resolve-value
                  :searchDataset resolve-value}})

(def streamers
  {:Subscription {:datasetEntities #'dataset/dataset-entities-subscription
                  :searchDataset #'dataset/search-dataset-subscription}})

(defn load-schema []
  (-> (io/resource "datapub/schema.graphql")
      slurp
      (pschema/parse-schema {:resolvers resolvers
                             :scalars scalars
                             :streamers streamers})
      schema/compile))
