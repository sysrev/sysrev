(ns sysrev.db.articles
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction  scorify-article]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn label-confirmed-test [confirmed?]
  (case confirmed?
    true [:!= :confirm_time nil]
    false [:= :confirm_time nil]
    true))

(defn all-overall-labels []
  (->>
   (-> (select :a.article_id :ac.user_id :ac.answer)
       (from [:article :a])
       (join [:article_criteria :ac]
             [:= :ac.article_id :a.article_id])
       (merge-join [:criteria :c]
                   [:= :ac.criteria_id :c.criteria_id])
       (where [:and
               [:= :c.name "overall include"]
               [:!= :ac.confirm_time nil]
               [:!= :ac.answer nil]])
       do-query)
   (group-by :article_id)))

(defn all-label-conflicts []
  (->> (all-overall-labels)
       (filter (fn [[aid labels]]
                 (< 1 (->> labels (map :answer) distinct count))))
       (apply concat)
       (apply hash-map)))

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

(defn all-labeled-articles [& [confirmed?]]
  (->> (-> (select :*)
           (from [:article :a])
           (join [:article_ranking :r] [:= :a.article_id :r._1])
           (where [:exists
                   (-> (select :*)
                       (from [:article_criteria :ac])
                       (where
                        [:and
                         [:= :ac.article_id :a.article_id]
                         (label-confirmed-test confirmed?)]))])
           do-query)
       (group-by :article_id)
       (map-values first)
       (map-values scorify-article)))

(defn get-unlabeled-articles [fields n-max above-score & [confirmed?]]
  (let [above-score (or above-score -1.0)]
    (->> (-> (apply select :article_id :r._2 fields)
             (from [:article :a])
             (join [:article_ranking :r] [:= :a.article_id :r._1])
             (where [:and
                     [:> :r._2 above-score]
                     [:not
                      [:exists
                       (-> (select :*)
                           (from [:article_criteria :ac])
                           (where
                            [:and
                             [:= :ac.article_id :a.article_id]
                             (label-confirmed-test confirmed?)]))]]])
             (order-by :r._2)
             (#(if n-max (limit % n-max) (identity %)))
             do-query)
         (map scorify-article))))

(defn all-article-labels [confirmed? & label-keys]
  (let [article-ids (->> (-> (select :article_id)
                             (from :article_criteria)
                             (where
                              (label-confirmed-test confirmed?))
                             (modifiers :distinct)
                             do-query)
                         (map :article_id))
        labels (-> (apply select :article_id :confirm_time label-keys)
                   (from :article_criteria)
                   (where
                    (label-confirmed-test confirmed?))
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
                             (map #(select-keys
                                    % (conj label-keys :confirm_time)))))
                      alabels
                      (->> alabels
                           (map
                            #(assoc % :confirmed
                                    (not (nil? (:confirm_time %)))))
                           (map #(dissoc % :confirm_time)))]
                  [article-id alabels])))
         (apply concat)
         (apply hash-map))))

(defn all-labels-for-article [article-id]
  (-> (select :ac.*)
      (from [:article_criteria :ac])
      (where [:= :ac.article_id article-id])
      do-query))

(defn get-n-labeled-articles
  "Queries for articles that have confirmed labels from `user-count` distinct
  users. Excludes articles that have been labeled by `self-id`. Sorts by article
  ranking score, excluding articles with score <= `above-score`. `n-max` is
  used in LIMIT clause, or may be nil."
  [self-id user-count n-max & [above-score]]
  (let [above-score (or above-score -1.0)]
    (-> (select :a.* [(sql/call :min :r._2) :score])
        (from [:article :a])
        (join [:article_criteria :ac] [:= :ac.article_id :a.article_id])
        (merge-join [:article_ranking :r] [:= :a.article_id :r._1])
        (where [:and
                [:!= :ac.answer nil]
                [:!= :ac.confirm_time nil]
                [:> :r._2 above-score]])
        (group :a.article_id)
        (having [:and
                 (sql/call :every (sql/raw (str "ac.user_id != " self-id)))
                 [:= user-count (sql/call :count (sql/call :distinct :ac.user_id))]])
        (order-by (sql/call :min :r._2))
        (#(if n-max (limit % n-max) (identity %)))
        do-query)))

