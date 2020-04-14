(ns sysrev.project.graphql
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [com.walmartlabs.lacinia.executor :as executor]
            [honeysql.helpers :as sqlh :refer [merge-where select from where join]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.datasource.api :as ds-api]
            [sysrev.graphql.core :refer [with-graphql-auth]]
            [sysrev.util :as util :refer [index-by map-values gquery]]))

(defn- merge-label-definitions [m project-id]
  (assoc m :labelDefinitions
         (q/find :label {:project-id project-id}
                 [[:label-id :id] [:short-label :name]
                  :value-type :definition :question :required :enabled :consensus])))

(defn- merge-articles [m project-id]
  (assoc m :articles
         (-> (select :a.enabled
                     [:a.article_id :id]
                     [:a.article_uuid :uuid]
                     [:ad.external_id :datasource_id])
             (from [:article :a])
             (join [:article_data :ad] [:= :ad.article_data_id :a.article_data_id])
             (where [:= :project_id project-id])
             do-query)))

(defn- merge-article-labels [m project-id]
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
                             ;; this could cause problems if we ever have
                             ;; non-vector or single value answers
                             (->> (map #(let [answer (:answer %)]
                                          (if-not (vector? answer)
                                            (assoc % :answer (vector (str answer)))
                                            %))))))
        articles (:articles m)]
    (assoc m :articles
           (map #(assoc % :labels
                        (get labels (:id %))) articles))))

(defn- merge-article-reviewers [m]
  (let [{:keys [articles]} m
        all-user-ids (->> articles (map :labels) flatten (map :user-id) distinct
                          (remove nil?))
        reviewers (->> (q/find :web-user {:user-id all-user-ids}
                               [[:user-id :id] [:email :name]])
                       (map #(assoc % :name (first (str/split (:name %) #"@"))))
                       (group-by :id))]
    (assoc m :articles
           (let [f (fn [[k v]] (if (= :user-id k)
                                 [:reviewer (first (get reviewers v))]
                                 [k v]))]
             ;; only apply to maps
             (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) articles)))))

(defn- merge-article-content [{:keys [articles] :as m} ds-api-token]
  (let [id->external (q/find [:article :a] {:a.article-id (distinct (map :id articles))}
                             :ad.external-id, :join [:article-data:ad :a.article-data-id]
                             :index-by :article-id)
        entities (-> (gquery [[:entities {:ids (vals id->external)}
                               [:id :content]]])
                     (ds-api/run-ds-query :auth-key ds-api-token)
                     (get-in [:body :data :entities])
                     (->> (index-by :id)))]
    (update m :articles
            (partial map #(assoc % :content (get-in entities [(id->external (:id %))
                                                              :content]))))))

(defn ^ResolverResult project [context {:keys [id]} _]
  (let [project-id id
        api-token (:authorization context)]
    (with-graphql-auth {:api-token api-token :project-id project-id :project-role "admin"}
      (let [selections-seq (executor/selections-seq context)]
        (resolve-as
         (cond-> (q/query-project-by-id id [:date-created [:project-id :id] :name])
           (some {:Project/labelDefinitions true} selections-seq)
           (merge-label-definitions id)
           (some {:Project/articles true} selections-seq)
           (merge-articles id)
           (some {:Article/labels true} selections-seq)
           (merge-article-labels id)
           (some {:Reviewer/id true} selections-seq)
           (merge-article-reviewers)
           (some {:Article/content true} selections-seq)
           (merge-article-content api-token)))))))
