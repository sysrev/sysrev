(ns sysrev.graphql.core
  (:require
   [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
   [sysrev.datasource.api :as ds-api]
   [sysrev.project.core :as project]
   [sysrev.project.member :refer [member-role?]]
   [sysrev.project.plan :as pplan]
   [sysrev.user.interface :as user :refer [user-by-api-token]]
   [sysrev.util :as util :refer [gquery]]))

(defn fail [message & [more]]
  (resolve-as nil [(cond-> {:message message}
                     more (merge more))]))

(defn authorization-error [sr-context {:keys [api-token project-id project-role]}]
  (let [user-id (some-> api-token (user-by-api-token) :user-id)]
    (cond (not (seq api-token))
          (fail "api-token not supplied in request headers as Authorization: Bearer <api-token>")
          ;; Allow sysrev to make queries to itself
          (= api-token (ds-api/ds-auth-key))
          nil

          (not user-id)
          (fail "api-token is not associated with a user")

          (and project-id user-id
               (not (member-role? project-id user-id project-role)))
          (fail (format "You do not have the role of \"%s\" for project #%d"
                        project-role project-id))

          (and project-id (not (project/project-exists? project-id)))
          (fail (format "Project #%d does not exist" project-id))

          (and project-id
               (not (or (-> (project/project-settings project-id)
                            ((comp true? :public-access)))
                        (pplan/project-unlimited-access? project-id)
                        (user/dev-user? sr-context user-id))))
          (fail "This request requires an upgraded plan"))))

(defmacro with-graphql-auth [sr-context opts & body]
  `(or (authorization-error ~sr-context ~opts)
       (do ~@body)))

(defmacro with-datasource-proxy [sr-context
                                 result
                                 {:keys [query api-token project-id project-role]}
                                 & body]
  `(with-graphql-auth
     ~sr-context
     {:api-token ~api-token
      :project-id ~project-id
      :project-role ~project-role}
     (let [query# ~query
           ~result (ds-api/run-ds-query (cond-> query#
                                          (vector? query#) (gquery))
                                        :auth-key ~api-token)]
       (cond (not= 200 (:status ~result))
             (fail (format "GraphQL on %s/graphql query failed with errors" (ds-api/ds-host))
                   {:errors (get-in ~result [:body :errors])})
             :else (do ~@body)))))
