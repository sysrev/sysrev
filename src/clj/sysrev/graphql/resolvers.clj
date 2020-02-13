(ns sysrev.graphql.resolvers
  (:require [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [honeysql.helpers :as sqlh :refer [merge-where select from where join]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.project.article-list :as alist]
            [sysrev.source.interface :refer [import-source]]
            [sysrev.user.core :refer [user-by-api-token]]
            [sysrev.util :as util]
            [venia.core :as venia]))

(defn assoc-label-definitions
  [m project-id]
  (assoc m :labelDefinitions
         (-> (select [:label_id :id]
                     :value_type
                     [:short_label :name]
                     :definition
                     :question
                     :required
                     :enabled
                     :consensus)
             (from :label)
             (where [:= :project_id project-id])
             do-query)))

(defn assoc-articles
  [m project-id]
  (assoc m :articles
         (-> (select :enabled
                     [:article_id :id]
                     [:article_uuid :uuid])
             (from :article)
             (where [:= :project_id project-id])
             do-query)))

(defn assoc-article-labels
  [m project-id]
  (let [labels (group-by :article-id
                         (-> (select [:l.label_id :id]
                                     [:l.value_type :type]
                                     [:l.short_label :name]
                                     :l.question
                                     :l.required
                                     :l.consensus
                                     :l.required
                                     :al.answer
                                     [:al.added-time :created]
                                     [:al.updated-time :updated]
                                     [:al.confirm_time :confirmed]
                                     [:al.article_id :article_id]
                                     :al.user_id)
                             (from [:article :a])
                             (join [:article_label :al] [:= :al.article_id :a.article_id]
                                   [:label :l] [:= :al.label_id :l.label_id])
                             (where [:= :a.project-id project-id])
                             do-query
                             ;; this could cause problems if we
                             ;; ever have non-vector or single value
                             ;; answers
                             (->> (map #(let [answer (:answer %)]
                                          (if-not (vector? answer)
                                            (assoc % :answer (vector (str answer)))
                                            %))))))
        articles (:articles m)]
    (assoc m :articles
           (map #(assoc % :labels
                        (get labels (:id %))) articles))))

(defn assoc-article-reviewers
  [m]
  (let [articles (:articles m)
        article-user-ids (->> (map :labels articles)
                              flatten
                              (map :user-id)
                              distinct
                              (filter (comp not nil?)))
        reviewers (group-by :id (-> (select [:user_id :id]
                                            [:email :name])
                                    (from :web_user)
                                    (where [:in :user_id article-user-ids])
                                    do-query
                                    (->> (map #(assoc % :name (first (clojure.string/split (:name %) #"@")))))))]
    (assoc m :articles
           (let [f (fn [[k v]] (if (= :user-id k) [:reviewer (first (get reviewers v))] [k v]))]
             ;; only apply to maps
             (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) articles)))))

(defn assoc-article-content
  [m ds-api-token]
  (let [articles (:articles m)
        ;; distinct used because index-by below will fail if there are duplicates
        article-ids (distinct (map :id articles))
        datasource-ids (-> (select :a.article_id :ad.external_id)
                           (from [:article :a])
                           (join [:article_data :ad]
                                 [:= :a.article_data_id
                                  :ad.article_data_id])
                           (where [:in :a.article_id article-ids])
                           do-query
                           (->> (map :article-id)))
        datasource-content (-> (ds-api/run-ds-query
                                (venia/graphql-query {:venia/queries [[:entities {:ids datasource-ids} [:id :content]]]})
                                :auth-key ds-api-token)
                               (get-in [:body :data :entities])
                               (->> (sysrev.shared.util/index-by :id)))]
    (assoc m :articles
           (map #(assoc % :content (get-in datasource-content [(:id %) :content])) articles))))

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
          (let [selections-seq (executor/selections-seq context)]
            (resolve-as
             (cond-> (q/query-project-by-id id [:date_created [:project-id :id] :name])
               (some {:Project/labelDefinitions true} selections-seq)
               (assoc-label-definitions id)
               (some {:Project/articles true} selections-seq)
               (assoc-articles id)
               (some {:Article/labels true} selections-seq)
               (assoc-article-labels id)
               (some {:Reviewer/id true} selections-seq)
               (assoc-article-reviewers)
               (some {:Article/content true} selections-seq)
               (assoc-article-content api-token)))))))

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
