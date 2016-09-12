(ns sysrev.db.articles
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction  scorify-article]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn all-criteria []
  (-> (select :*)
      (from :criteria)
      (order-by :criteria_id)
      do-query))

(defn get-criteria-id [name]
  (-> (select :criteria_id)
      (from :criteria)
      (where [:= :name name])
      do-query
      first
      :criteria_id))

(defn alter-criteria [id values]
  (-> (sqlh/update :criteria)
      (sset values)
      (where [:= :criteria_id id])
      do-execute))

(defn get-ranked-articles [page-idx]
  (->> (-> (select :*)
           (from [:article :a])
           (join [:article_ranking :r] [:= :a.article_id :r._1])
           (order-by :r._2)
           (limit 100)
           (offset (* page-idx 100))
           do-query)
       (group-by :article_id)
       (map-values first)
       (map-values scorify-article)))

(defn all-labeled-articles []
  (->> (-> (select :*)
           (from [:article :a])
           (join [:article_ranking :r] [:= :a.article_id :r._1])
           (where [:exists
                   (-> (select :*)
                       (from [:article_criteria :ac])
                       (where [:= :ac.article_id :a.article_id]))])
           do-query)
       (group-by :article_id)
       (map-values first)
       (map-values scorify-article)))

(defn all-article-labels [& label-keys]
  (let [article-ids (->> (-> (select :article_id)
                             (from :article_criteria)
                             (modifiers :distinct)
                             do-query)
                         (map :article_id))
        labels (-> (select :*)
                   (from :article_criteria)
                   do-query)]
    (->> article-ids
         (map (fn [article-id]
                (let [alabels
                      (->> labels
                           (filter #(= (:article_id %) article-id)))
                      alabels
                      (if (empty? label-keys)
                        alabels
                        (->> alabels
                             (map #(select-keys % label-keys))))]
                  [article-id alabels])))
         (apply concat)
         (apply hash-map))))

(defn all-labels-for-article [article-id]
  (-> (select :ac.*)
      (from [:article_criteria :ac])
      (where [:= :ac.article_id article-id])
      do-query))
