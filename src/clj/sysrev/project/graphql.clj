(ns sysrev.project.graphql
  (:require [clojure.set :refer [rename-keys]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [com.walmartlabs.lacinia.executor :as executor]
            [honeysql.helpers :as sqlh :refer [select from where join]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.datasource.api :as ds-api]
            [sysrev.project.core :refer [project-labels]]
            [sysrev.graphql.core :refer [with-graphql-auth]]
            [sysrev.util :as util :refer [index-by gquery sanitize-uuids]]))

(defn- merge-label-definitions [m project-id]
  (let [labels (->> (project-labels project-id true)
                    vals
                    (walk/postwalk (fn [x] (if (map? x)
                                             (rename-keys x {:value-type :type})
                                             x)))
                    (walk/postwalk (fn [x] (if (and (map? x)
                                                    (contains? x :definition))
                                             (assoc x :definition
                                                    (-> x :definition json/write-str))
                                             x))))
        label-definitions (filter #(not= (:type %) "group") labels)
        group-label-definitions (->> (filter #(= (:type %) "group") labels)
                                     (map #(assoc % :labels (-> (:labels %) vals))))]
    (assoc m
           :labelDefinitions label-definitions
           :groupLabelDefinitions group-label-definitions)))

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

(defn process-group-labels
  "Given a project-id, convert :answer keys to the format required by "
  [project-id m]
  (let [group-labels (-> m
                         sanitize-uuids)
        group-label-definitions  (-> (select [:label-id :id] [:value_type :type] [:short_label :name]
                                             :question :required :consensus)
                                     (from :label)
                                     (where [:and
                                             [:= :project-id project-id]
                                             [:not= :root-label-id-local nil]])
                                     do-query
                                     (->> (index-by :id)))
        process-group-answers (fn [group-label]
                                (assoc group-label :answer
                                       (->> group-label :answer :labels vals
                                            (map
                                             (fn [answers]
                                               (map (fn [answer]
                                                      (merge
                                                       {:answer
                                                        ;; this is similar to below
                                                        ;; where all labels are put
                                                        ;; into a vector
                                                        (let [this-answer (second answer)]
                                                          (if-not (vector? this-answer)
                                                            (vector (str this-answer))
                                                            this-answer))}
                                                       (get group-label-definitions
                                                            (first answer)))) answers))))))]
    (map process-group-answers group-labels)))

(defn- merge-article-labels [m project-id]
  (let [labels (-> (select [:l.label_id :id]
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
                   (where  [:= :a.project-id project-id])
                   do-query)
        non-group-labels (->> (filter #(not= (:type %) "group") labels)
                              ;; this could cause problems if we ever have
                              ;; non-vector or single value answers
                              (map #(let [answer (:answer %)]
                                      (if-not (vector? answer)
                                        (assoc % :answer (vector (str answer)))
                                        %)))
                              (group-by :article-id))
        group-labels (->> (filter #(= (:type %) "group") labels)
                          (process-group-labels project-id)
                          (group-by :article-id))
        articles (:articles m)]
    (assoc m :articles
           (map #(assoc % :labels
                        (get non-group-labels (:id %))
                        :groupLabels
                        (get group-labels (:id %))) articles))))

(defn- merge-article-reviewers [m project-id]
  (let [{:keys [articles]} m
        all-user-ids (-> (select [:wu.user-id :id] [:wu.email :email] [:wu.name :name])
                         (from [:web_user :wu])
                         (join [:project_member :pm] [:= :pm.user_id :wu.user_id])
                         (where [:= :pm.project-id project-id])
                         do-query)
        reviewers (->> all-user-ids
                       (map #(assoc % :name (first (str/split (:email %) #"@"))))
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
           (some {:Article/labels true
                  :Article/groupLabels true} selections-seq)
           (merge-article-labels id)
           (some {:Reviewer/id true} selections-seq)
           (merge-article-reviewers id)
           (some {:Article/content true} selections-seq)
           (merge-article-content api-token)))))))
