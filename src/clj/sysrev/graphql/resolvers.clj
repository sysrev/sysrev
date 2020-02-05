(ns sysrev.graphql.resolvers
  (:require [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [honeysql.helpers :as sqlh :refer [merge-where select from where join]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.project.article-list :as alist]
            [sysrev.source.interface :refer [import-source]]
            [sysrev.user.core :refer [user-by-api-token]]
            [sysrev.util :as util]
            [venia.core :as venia]))

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

(defn ^ResolverResult import-dataset [context {:keys [id dataset]} _]
  (let [project-id id
        api-token (:authorization context)
        user (and api-token (user-by-api-token api-token))
        project-role "admin"
        member-roles (and user id
                          (-> (q/select-project-members project-id [:m.permissions])
                              (merge-where [:= :u.user-id (:user-id user)])
                              (->> do-query first :permissions)))
        query-result (ds-api/run-ds-query (venia/graphql-query {:venia/queries [[:dataset {:id dataset} [:name [:entities [:id]]]]]}) :auth-key api-token)]

    (cond (not (seq api-token))
          (resolve-as nil [{:message "api-token not supplied in request headers as Authorization: Bearer <api-token>"}])
          (not (seq user))
          (resolve-as false [{:message "api-token is not associated with a user"}])
          (not (seq member-roles))
          (resolve-as false [{:message (str "You do not have the role of " project-role " for project with id " id)}])
          (not (> dataset 7))
          (resolve-as false [{:message (str "That dataset can't be imported, please select a datasource with an id > 7")}])
          (not (= 200 (:status query-result)))
          (resolve-as false [{:message (str "GraphQL on " (ds-api/ds-host) "/graphql query failed with errors")
                              :errors (get-in query-result [:body :errors])}])
          :else
          (let [entities (get-in query-result [:body :data :dataset :entities])
                dataset-name (get-in query-result [:body :data :dataset :name])]
            (import-source :datasource-dataset project-id {:dataset-id dataset
                                                           :dataset-name dataset-name
                                                           :entities entities}
                           nil)
            (resolve-as true)))))

(defn ^ResolverResult import-datasource [context {:keys [id datasource]} _]
  (let [project-id id
        api-token (:authorization context)
        user (and api-token (user-by-api-token api-token))
        project-role "admin"
        member-roles (and user id
                          (-> (q/select-project-members project-id [:m.permissions])
                              (merge-where [:= :u.user-id (:user-id user)])
                              (->> do-query first :permissions)))
        query-result (ds-api/run-ds-query (venia/graphql-query {:venia/queries [[:datasource {:id datasource} [[:datasets [:id :name [:entities [:id]]]]]]]}) :auth-key api-token)]

    (cond (not (seq api-token))
          (resolve-as nil [{:message "api-token not supplied in request headers as Authorization: Bearer <api-token>"}])
          (not (seq user))
          (resolve-as false [{:message "api-token is not associated with a user"}])
          (not (seq member-roles))
          (resolve-as false [{:message (str "You do not have the role of " project-role " for project with id " id)}])
          (not (> datasource 3))
          (resolve-as false [{:message (str "That dataset can't be imported, please select a datasource with an id > 3")}])
          (not (= 200 (:status query-result)))
          (resolve-as false [{:message (str "GraphQL on " (ds-api/ds-host) "/graphql query failed with errors")
                              :errors (get-in query-result [:body :errors])}])
          :else
          (let [datasets (get-in query-result [:body :data :datasource :datasets])]
            (try
              (doall
               (map #(import-source :datasource-dataset project-id {:dataset-id (:id %)
                                                                    :dataset-name (:name %)
                                                                    :entities (:entities %)}
                                    nil) datasets))
              (resolve-as true)
              (catch Exception e
                (resolve-as false [{:message (str "There was an exception with message: " (.getMessage e))}])))))))

;; ok this is getting ridiculous... refactor ASAP!
(defn ^ResolverResult import-datasource-flattened [context {:keys [id datasource]} _]
  (let [project-id id
        api-token (:authorization context)
        user (and api-token (user-by-api-token api-token))
        project-role "admin"
        member-roles (and user id
                          (-> (q/select-project-members project-id [:m.permissions])
                              (merge-where [:= :u.user-id (:user-id user)])
                              (->> do-query first :permissions)))
        query-result (ds-api/run-ds-query
                      (venia/graphql-query {:venia/queries [[:datasource {:id datasource} [:name [:datasets [:id :name [:entities [:id]]]]]]]}) :auth-key api-token)]
    (cond (not (seq api-token))
          (resolve-as nil [{:message "api-token not supplied in request headers as Authorization: Bearer <api-token>"}])
          (not (seq user))
          (resolve-as false [{:message "api-token is not associated with a user"}])
          (not (seq member-roles))
          (resolve-as false [{:message (str "You do not have the role of " project-role " for project with id " id)}])
          (not (> datasource 3))
          (resolve-as false [{:message (str "That dataset can't be imported, please select a datasource with an id > 3")}])
          (not (= 200 (:status query-result)))
          (resolve-as false [{:message (str "GraphQL on " (ds-api/ds-host) "/graphql query failed with errors")
                              :errors (get-in query-result [:body :errors])}])
          :else
          (let [datasets (get-in query-result [:body :data :datasource :datasets])
                datasource-name (get-in query-result [:body :data :datasource :name])
                entities (medley.core/join (map :entities datasets))]
            (try
              (import-source :datasource-datasource project-id {:datasource-id datasource
                                                                :datasource-name datasource-name
                                                                :entities entities} nil)
              (resolve-as true)
              (catch Exception e
                (resolve-as false [{:message (str "There was an exception with message: " (.getMessage e))}])))))))

(defn extract-filters-from-url
  "Covert a url string into a vector of filters that can be passed to query-project-article-ids"
  [s]
  (let [{:keys [filters text-search]}
        (-> (ring.util.codec/form-decode s)
            (clojure.walk/keywordize-keys)
            (select-keys [:filters :text-search])
            (update-in [:filters] #(clojure.data.json/read-str % :key-fn keyword))
            (util/convert-uuids))]
    (vec (concat filters (when text-search [{:text-search text-search}])))))

(defn ^ResolverResult import-article-filter-url! [_context {url :url source-id :sourceID target-id :targetID} _]
  (let [filters (extract-filters-from-url url)
        articles (alist/query-project-article-ids {:project-id source-id} filters)
        entities (-> (select [:ad.external-id :id])
                     (from [:article-data :ad])
                     (join [:article :a]
                           [:= :ad.article-data-id :a.article-data-id])
                     (where [:in :a.article-id articles])
                     do-query)]
    (cond (= source-id target-id)
          (resolve-as nil [{:message "source-id can not be the same as target-id"}])
          :else
          (try
            (import-source :datasource-project-url-filter target-id {:url-filter url
                                                                     :source-id source-id
                                                                     :entities entities}
                           nil)
            (resolve-as true)
            (catch Exception e
              (resolve-as false [{:message (str "There was an exception with message: " (.getMessage e))}]))))))
