(ns sysrev.db.articles
  (:require [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn all-criteria []
  (-> (select :*)
      (from :criteria)
      (order-by :criteria_id)
      do-query))

(defn get-ranked-articles [page-idx]
  (->> (-> (select :*)
           (from [:article :a])
           (join [:article_ranking :r] [:= :a.article_id :r._1])
           (order-by :r._2)
           (limit 100)
           (offset (* page-idx 100))
           do-query)
       (mapv #(let [score (:_2 %)
                    item (dissoc % :_1 :_2)]
                [(:article_id item)
                 {:item item :score score}]))
       (apply concat)
       (apply hash-map)))
#_
(defn all-labeled-articles []
  (->> (-> (select :a.*)
           (from [:article_criteria :ac])
           (join [:article :a] [:= :ac.article_id :a.article_id])
           do-query)
       (group-by )))

(defn all-labeled-articles []
  (->> (-> (select :*)
           (from [:article :a])
           (join [:article_ranking :r] [:= :a.article_id :r._1])
           (where [:exists
                   (-> (select :*)
                       (from [:article_criteria :ac])
                       (where [:= :ac.article_id :a.article_id]))])
           do-query)
       (map (fn [entry]
              (let [id (:article_id entry)
                    score (:_2 entry)
                    item (dissoc entry :_1 :_2)]
                [id {:item item :score score}])))
       (apply concat)
       (apply hash-map)))

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
