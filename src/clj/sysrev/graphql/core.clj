(ns sysrev.graphql.core
  (:require [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
            [sysrev.project.member :refer [member-role?]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.user.interface :refer [user-by-api-token]]
            [sysrev.util :as util :refer [gquery]]))

(defn fail [message & [more]]
  (resolve-as false [(cond-> {:message message}
                       more (merge more))]))

(defmacro with-graphql-auth [{:keys [api-token project-id project-role]}
                             & body]
  `(let [project-role# ~project-role
         project-id# ~project-id
         api-token# ~api-token
         user-id# (some-> api-token# (user-by-api-token) :user-id)]
     (cond (not (seq api-token#))
           (fail "api-token not supplied in request headers as Authorization: Bearer <api-token>")
           ;; this to allow sysrev to make queries on itself
           (= api-token# (ds-api/ds-auth-key))
           (do ~@body)
           (not user-id#)
           (fail "api-token is not associated with a user")
           (and project-id# user-id#
                (not (member-role? project-id# user-id# project-role#)))
           (fail (format "You do not have the role of \"%s\" for project #%d"
                         project-role# project-id#))
           :else (do ~@body))))

(defmacro with-datasource-proxy [result
                                 {:keys [query api-token project-id project-role]}
                                 & body]
  `(with-graphql-auth {:api-token ~api-token
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
