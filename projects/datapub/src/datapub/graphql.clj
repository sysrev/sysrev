(ns datapub.graphql
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.parser.schema :as pschema]
            [com.walmartlabs.lacinia.schema :as schema]
            [datapub.dataset :as dataset]
            [sysrev.lacinia.interface :as sl]))

(def resolvers
  {:Dataset {:entities #'dataset/resolve-Dataset#entities
             :indices #'dataset/resolve-Dataset#indices}
   :DatasetEntitiesEdge {:node #'dataset/resolve-DatasetEntitiesEdge#node}
   :ListDatasetsEdge {:node #'dataset/resolve-ListDatasetsEdge#node}
   :Query {:dataset #'dataset/resolve-dataset
           :datasetEntitiesById #'dataset/resolve-datasetEntitiesById
           :datasetEntity #'dataset/resolve-dataset-entity
           :listDatasets #'dataset/list-datasets}
   :Mutation {:createDataset #'dataset/create-dataset!
              :createDatasetEntity #'dataset/create-dataset-entity!
              :createDatasetIndex #'dataset/create-dataset-index!
              :updateDataset #'dataset/update-dataset!}
   :Subscription {:datasetEntities sl/resolve-value
                  :searchDataset sl/resolve-value}})

(def streamers
  {:Subscription {:datasetEntities #'dataset/dataset-entities-subscription
                  :searchDataset #'dataset/search-dataset-subscription}})

(defn load-schema []
  (-> (io/resource "datapub/schema.graphql")
      slurp
      (str (slurp (io/resource "datapub/schema-subscription.graphql")))
      (pschema/parse-schema {:resolvers resolvers
                             :scalars sl/scalars
                             :streamers streamers})
      schema/compile))
