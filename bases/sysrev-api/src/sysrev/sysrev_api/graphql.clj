(ns sysrev.sysrev-api.graphql
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.parser.schema :as pschema]
            [com.walmartlabs.lacinia.schema :as schema]
            [sysrev.lacinia.interface :as sl]
            [sysrev.sysrev-api.project :as project]))

(def resolvers
  {:Query {:project #'project/resolve-project}
   :Mutation {:createProject #'project/createProject!}})

(def streamers
  {})

(defn load-schema []
  (-> (io/resource "sysrev-api/schema.graphql")
      slurp
      (pschema/parse-schema {:resolvers resolvers
                             :scalars sl/scalars
                             :streamers streamers})
      schema/compile))
