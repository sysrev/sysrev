(ns sysrev.sysrev-api.graphql
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.parser.schema :as pschema]
            [com.walmartlabs.lacinia.schema :as schema]
            [sysrev.lacinia.interface :as s-lacin]))

(def resolvers
  {})

(def streamers
  {})

(defn load-schema []
  (-> (io/resource "sysrev-api/schema.graphql")
      slurp
      (pschema/parse-schema {:resolvers resolvers
                             :scalars s-lacin/scalars
                             :streamers streamers})
      schema/compile))
