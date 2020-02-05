(ns sysrev.graphql.schema
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
   [sysrev.graphql.resolvers :as resolvers]))

(defn sysrev-schema
  []
  (-> (io/resource "edn/graphql-schema.edn")
      slurp
      edn/read-string
      (attach-resolvers {:resolve-project resolvers/project
                         :resolve-import-articles! resolvers/import-articles
                         :resolve-import-dataset! resolvers/import-dataset
                         :resolve-import-datasource! resolvers/import-datasource
                         :resolve-import-datasource-flattened! resolvers/import-datasource-flattened
                         :resolve-import-article-filter-url! resolvers/import-article-filter-url!})
      (schema/compile {:default-field-resolver schema/hyphenating-default-field-resolver})))
