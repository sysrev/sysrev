(ns sysrev.project-api.interface
  (:require [sysrev.project-api.core :as core]
            [sysrev.project-api.source :as source]))

(def ^{:doc "A map of lacinia resolvers."}
  resolvers
  {:CreateProjectLabelPayload {:projectLabel #'core/resolve-create-project-label-payload#project-label}
   :CreateProjectPayload {:project #'core/resolve-project-field}
   :CreateProjectSourcePayload {:projectSource #'source/resolve-project-source-field}
   :Project {:labels #'core/resolve-project#labels}
   :ProjectLabel {:project #'core/resolve-project-field}
   :ProjectSource {:project #'core/resolve-project-field}
   :Query {:getProject #'core/get-project
           :getProjectLabel #'core/get-project-label
           :getProjectSource #'source/get-project-source}
   :Mutation {:createProject #'core/create-project!
              :createProjectLabel #'core/create-project-label!
              :createProjectSource #'source/create-project-source!}})

(defn project-name-error
  "Returns an error message if the project name is valid, or else nil."
  [name]
  (core/project-name-error name))
