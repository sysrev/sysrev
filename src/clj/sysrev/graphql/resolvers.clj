(ns sysrev.graphql.resolvers
  (:require [honeysql.helpers :as sqlh :refer [merge-where]]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.source.interface :refer [import-source]]
            [sysrev.user.core :refer [user-by-api-token]]))

(defn ^ResolverResult project [context {:keys [id]} _]
  (let [api-token (:authorization context)
        user (and api-token (user-by-api-token api-token))
        project-role "admin"
        member-roles (and user id
                          (-> (q/select-project-members id [:m.permissions])
                              (merge-where [:= :u.user-id (:user-id user)])
                              (->> do-query first :permissions)))]
    (cond (not (seq api-token))
          (resolve-as nil [{:message "api-token not supplied in request headers as Authorization: Bearer <api-token>"}])
          (not (seq user))
          (resolve-as nil [{:message "api-token is not associated with a user"}])
          (not (seq member-roles))
          (resolve-as nil [{:message (str "You do not have the role of " project-role " for project with id " id)}])
          :else
          (resolve-as (q/query-project-by-id id [:*]))))  )

(defn ^ResolverResult import-articles [context {:keys [id query]} _]
  (let [project-id id
        api-token (:authorization context)
        user (and api-token (user-by-api-token api-token))
        project-role "admin"
        member-roles (and user id
                          (-> (q/select-project-members project-id [:m.permissions])
                              (merge-where [:= :u.user-id (:user-id user)])
                              (->> do-query first :permissions)))
        query-result (ds-api/run-ds-query query :auth-key api-token)]

    (cond (not (seq api-token))
          (resolve-as nil [{:message "api-token not supplied in request headers as Authorization: Bearer <api-token>"}])
          (not (seq user))
          (resolve-as false [{:message "api-token is not associated with a user"}])
          (not (seq member-roles))
          (resolve-as false [{:message (str "You do not have the role of " project-role " for project with id " id)}])
          (not (= 200 (:status query-result)))
          (resolve-as false [{:message (str "GraphQL on " (ds-api/ds-host) "/graphql query failed with errors")
                              :errors (get-in query-result [:body :errors])}])
          :else
          (let [entities
                ;; https://stackoverflow.com/questions/28091305/find-value-of-specific-key-in-nested-map
                ;; this is hack which assumes that 
                ;; only vectors will contain entities in
                ;; a response. 
                (->> (get-in query-result [:body])
                     (tree-seq map? vals)
                     (filter vector?)
                     flatten
                     (into []))]
            (import-source :datasource project-id {:query query
                                                   :entities entities}
                           nil)
            (resolve-as true)))))
