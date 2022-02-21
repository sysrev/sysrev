(ns sysrev.sysrev-api.graphql
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.parser.schema :as pschema]
            [com.walmartlabs.lacinia.schema :as schema]
            [sysrev.lacinia.interface :as sl]
            [sysrev.sysrev-api.project :as project]))

(def resolvers
  {:CreateProjectLabelPayload {:projectLabel #'project/resolve-create-project-label-payload#project-label}
   :CreateProjectPayload {:project #'project/resolve-create-project-payload#project}
   :Project {:labels #'project/resolve-project#labels}
   :ProjectLabel {:project #'project/resolve-project-label#project}
   :Query {:getProject #'project/get-project
           :getProjectLabel #'project/get-project-label}
   :Mutation {:createProject #'project/create-project!
              :createProjectLabel #'project/create-project-label!}})

(def streamers
  {})

(defn load-schema []
  (-> (io/resource "sysrev-api/schema.graphql")
      slurp
      (pschema/parse-schema {:resolvers resolvers
                             :scalars sl/scalars
                             :streamers streamers})
      schema/compile))
