(ns datapub.graphql
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.parser.schema :as pschema]
            [com.walmartlabs.lacinia.schema :as schema]
            [datapub.dataset :as dataset])
  (:import java.sql.Timestamp
           java.time.format.DateTimeFormatter
           java.time.Instant))

(set! *warn-on-reflection* true)

(def scalars
  {:DateTime {:parse (fn [x]
                       (-> (try
                             (when (string? x)
                               (-> (.parse DateTimeFormatter/ISO_INSTANT x)
                                   Instant/from))
                             (catch Exception _))
                           (or
                            (throw
                             (ex-info "Must be a string representing a DateTime in ISO-8061 instant format, such as \"2011-12-03T10:15:30Z\"."
                                      {:value x})))))
              :serialize (fn serialize [datetime]
                           (cond
                             (instance? Instant datetime)
                             (.format DateTimeFormatter/ISO_INSTANT datetime)

                             (instance? Timestamp datetime)
                             (serialize (.toInstant ^Timestamp datetime))))}
   :NonNegativeInt {:parse (fn [x]
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
  {:Dataset {:entities #'dataset/resolve-Dataset-entities
             :indices #'dataset/resolve-Dataset-indices}
   :DatasetEntitiesEdge {:node #'dataset/resolve-DatasetEntitiesEdge-node}
   :ListDatasetsEdge {:node #'dataset/resolve-ListDatasetsEdge-node}
   :Query {:dataset #'dataset/resolve-dataset
           :datasetEntity #'dataset/resolve-dataset-entity
           :listDatasets #'dataset/list-datasets}
   :Mutation {:createDataset #'dataset/create-dataset!
              :createDatasetEntity #'dataset/create-dataset-entity!
              :createDatasetIndex #'dataset/create-dataset-index!
              :updateDataset #'dataset/update-dataset!}
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
