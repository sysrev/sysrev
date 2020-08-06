(ns sysrev.project.graphql
  (:require [clojure.set :refer [rename-keys]]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [com.walmartlabs.lacinia.executor :as executor]
            [sysrev.db.queries :as q]
            [sysrev.datasource.api :as ds-api]
            [sysrev.project.core :refer [project-labels project-user-ids]]
            [sysrev.graphql.core :refer [with-graphql-auth]]
            [sysrev.util :as util :refer [index-by gquery sanitize-uuids]]))

(defn- merge-label-definitions [m project-id]
  (let [labels (->> (project-labels project-id true)
                    vals
                    (walk/postwalk (fn [x] (if (map? x)
                                             (rename-keys x {:value-type :type
                                                             :short-label :name
                                                             :label-id :id
                                                             :project-ordering :ordering})
                                             x)))
                    (walk/postwalk #(cond-> %
                                      (and (map? %) (contains? % :definition))
                                      (update :definition json/write-str))))
        group? #(= (:type %) "group")]
    (merge m {:labelDefinitions (remove group? labels)
              :groupLabelDefinitions (->> (filter group? labels)
                                          (map #(update % :labels vals)))})))

(defn- merge-articles [m project-id]
  (assoc m :articles
         (q/find [:article :a] {:project-id project-id}
                 [:a.enabled [:a.article-id :id] [:a.article-uuid :uuid]
                  :ad.datasource-name [:ad.external-id :datasource-id]]
                 :join [[:article-data :ad] :a.article-data-id])))

(defn process-group-labels
  "Given a project-id, convert :answer keys to the format required by "
  [project-id m]
  (let [definitions (q/find :label {:project-id project-id}
                            [[:label-id :id] [:value-type :type] [:short-label :name]
                             :question :required :consensus]
                            :where [:!= :root-label-id-local nil]
                            :index-by :label-id)]
    (doall (for [group-label (sanitize-uuids m)]
             (assoc group-label :answer
                    (doall (for [answers (-> group-label :answer :labels vals)]
                             (doall (for [answer answers]
                                      (merge {:answer (let [this-answer (second answer)]
                                                        (if-not (vector? this-answer)
                                                          (vector (str this-answer))
                                                          this-answer))}
                                             (get definitions (first answer))))))))))))

(defn- merge-article-labels [m project-id]
  (let [labels (q/find [:article :a] {:a.project-id project-id}
                       ;; TODO - `consensus` is label definition, not answer status
                       [[:l.label-id :id] [:l.value-type :type] [:l.short-label :name]
                        :l.question :l.required :l.consensus :al.article-id :al.user-id
                        :al.answer [:al.added-time :created] [:al.updated-time :updated]
                        [:al.confirm-time :confirmed] [:ar.resolve-time :resolve]]
                       :join [[[:article-label :al] :a.article-id]
                              [[:label :l] :al.label-id]]
                       :left-join [[:article-resolve :ar] [:and [:= :al.user-id :ar.user-id]
                                                           [:= :a.article-id :ar.article-id]]])
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
                          (group-by :article-id))]
    (assoc m :articles
           (doall (for [{:keys [id] :as article} (:articles m)]
                    (merge article {:labels (get non-group-labels id)
                                    :groupLabels (get group-labels id)}))))))

(defn- merge-article-reviewers [{:keys [articles] :as m} project-id]
  (let [users (->> (q/find :web-user {:user-id (project-user-ids
                                                project-id :return :query)}
                           [[:user-id :id] :email]
                           :index-by :user-id)
                   (util/map-values #(assoc % :name (-> % :email util/email->name))))]
    (assoc m :articles
           (let [f (fn [[k v]] (if (= :user-id k)
                                 [:reviewer (get users v)]
                                 [k v]))]
             ;; only apply to maps
             (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) articles)))))

(defn- merge-article-content [{:keys [articles] :as m} ds-api-token]
  (let [id->external (q/find [:article :a] {:a.article-id (distinct (map :id articles))}
                             :ad.external-id, :join [[:article-data :ad] :a.article-data-id]
                             :index-by :article-id)
        entities (-> (gquery [[:entities {:ids (vals id->external)}
                               [:id :content]]])
                     (ds-api/run-ds-query :auth-key ds-api-token)
                     (get-in [:body :data :entities])
                     (->> (index-by :id)))]
    (update m :articles
            (partial map #(assoc % :content (get-in entities [(id->external (:id %))
                                                              :content]))))))

(defonce selections-seq-atom (atom {}))
(defn ^ResolverResult project [context {:keys [id]} _]
  (let [project-id id
        api-token (:authorization context)]
    (with-graphql-auth {:api-token api-token :project-id project-id :project-role "admin"}
      (let [selections-seq (executor/selections-seq context)]
        (reset! selections-seq-atom selections-seq)
        (resolve-as
         (cond-> (q/query-project-by-id id [:date-created [:project-id :id] :name])
           (some {:Project/labelDefinitions true
                  :Project/groupLabelDefinitions true} selections-seq)
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
