(ns sysrev.graphql.schema
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
   [datasource.entity :as entity]
   [datasource.ris :as ris]))

(defn datasource-schema
  []
  (-> (io/resource "edn/graphql-schema.edn")
      slurp
      edn/read-string
      (attach-resolvers {:resolve-entity entity/resolve-entity
                         :resolve-entities entity/resolve-entities
                         :resolve-pubmed-entities entity/resolve-pubmed-entities
                         :resolve-search-pubmed-entities entity/resolve-search-pubmed-entities
                         :resolve-ct-entities entity/resolve-ct-entities
                         :resolve-ris-file-citations-by-file-hash ris/resolve-ris-file-citations-by-file-hash
                         :resolve-ris-file-citations-by-ids ris/resolve-ris-file-citations-by-ids})
      schema/compile))
