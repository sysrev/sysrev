(ns sysrev.db.articles
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction]]
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

(defn all-labeled-articles [& [confirmed?]]
  (->> (-> (select :a.* [:lp.val :score])
           (from [:article :a])
           (join [:label_predicts :lp] [:= :a.article_id :lp.article_id])
           (merge-join [:criteria :c] [:= :lp.criteria_id :c.criteria_id])
           (where [:and
                   [:= :lp.sim_version_id 1]
                   [:= :lp.predict_version_id 1]
                   [:= :c.name "overall include"]
                   [:= :lp.stage 0]
                   [:exists
                    (-> (select :*)
                        (from [:article_criteria :ac])
                        (where
                         [:and
                          [:= :ac.article_id :a.article_id]
                          (label-confirmed-test confirmed?)]))]])
           do-query)
       (group-by :article_id)
       (map-values first)
       ;; there are some `article` entries with duplicate document_ids
       (map-values #(update % :document_ids distinct))))

(defn random-unlabeled-article []
  (let [article-ids
        (->>
         (-> (select :article_id)
             (from [:article :a])
             (where [:not
                     [:exists
                      (-> (select :*)
                          (from [:article_criteria :ac])
                          (where
                           [:and
                            [:= :ac.article_id :a.article_id]
                            [:!= :ac.answer nil]]))]])
             (limit 500)
             do-query)
         (map :article_id))
        random-id (nth article-ids (rand-int (count article-ids)))]
    (-> (select :a.* [:lp.val :score])
        (from [:article :a])
        (join [:label_predicts :lp] [:= :a.article_id :lp.article_id])
        (merge-join [:criteria :c] [:= :lp.criteria_id :c.criteria_id])
        (where [:and
                [:= :a.article_id random-id]
                [:= :lp.sim_version_id 1]
                [:= :lp.predict_version_id 1]
                [:= :c.name "overall include"]
                [:= :lp.stage 0]])
        do-query
        first)))

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

(defn get-single-labeled-articles
  "The purpose of this function is to find articles that have a confirmed
  inclusion label from exactly one user that is not `self-id`, to present
  to `self-id` to label the article a second time.

  Articles which have any labels saved by `self-id` (even unconfirmed) will
  be excluded from this query.

  If specified, the article score must be < than `above-score`. This is used
  to pass in the score of the user's current article task, allowing the results
  of this query to form a queue ordered by score across multiple requests."
  [self-id n-max & [above-score]]
  (let [above-score (or above-score 1.5)]
    (-> (select :a.* [(sql/call :max :lp.val) :score])
        (from [:article :a])
        (join [:article_criteria :ac] [:= :ac.article_id :a.article_id])
        (merge-join [:criteria :c] [:= :c.criteria_id :ac.criteria_id])
        (merge-join [:label_predicts :lp] [:= :a.article_id :lp.article_id])
        (where [:and
                [:= :c.name "overall include"]
                [:!= :ac.user_id self-id]
                [:!= :ac.answer nil]
                [:!= :ac.confirm_time nil]
                [:= :lp.sim_version_id 1]
                [:= :lp.predict_version_id 1]
                [:= :lp.stage 0]
                [:< :lp.val above-score]])
        (group :a.article_id)
        (having [:and
                 ;; one user found with a confirmed inclusion label
                 [:= 1 (sql/call :count (sql/call :distinct :ac.user_id))]
                 ;; and `self-id` has not labeled the article
                 [:not
                  [:exists
                   (-> (select :*)
                       (from [:article_criteria :ac2])
                       (where [:and
                               [:= :ac2.article_id :a.article_id]
                               [:= :ac2.user_id self-id]
                               [:!= :ac2.answer nil]]))]]])
        (order-by [(sql/call :max :lp.val) :desc])
        (#(if n-max (limit % n-max) (identity %)))
        do-query)))

(defn get-conflict-articles
  "The purpose of this function is to find articles with conflicting labels,
  to present to user `self-id` to resolve the conflict by labeling. These are
  the first priority in the classify queue.

  Queries for articles with conflicting confirmed inclusion labels from two
  users who are not `self-id`, and for which the article has no labels saved
  by `self-id` (even unconfirmed).

  If specified, the article score must be < than `above-score`. This is used
  to pass in the score of the user's current article task, allowing the results
  of this query to form a queue ordered by score across multiple requests."
  [self-id n-max & [above-score]]
  (let [above-score (or above-score 1.5)]
    (-> (select :a.* [(sql/call :max :lp.val) :score])
        (from [:article :a])
        (join [:article_criteria :ac] [:= :ac.article_id :a.article_id])
        (merge-join [:criteria :c] [:= :c.criteria_id :ac.criteria_id])
        (merge-join [:label_predicts :lp] [:= :a.article_id :lp.article_id])
        (where [:and
                [:= :c.name "overall include"]
                [:!= :ac.user_id self-id]
                [:!= :ac.answer nil]
                [:!= :ac.confirm_time nil]
                [:= :lp.sim_version_id 1]
                [:= :lp.predict_version_id 1]
                [:= :lp.stage 0]
                [:< :lp.val above-score]])
        (group :a.article_id)
        (having [:and
                 ;; article has two differing inclusion labels
                 [:= 2 (sql/call :count (sql/call :distinct :ac.user_id))]
                 [:= 2 (sql/call :count (sql/call :distinct :ac.answer))]
                 ;; and `self-id` has not labeled the article
                 [:not
                  [:exists
                   (-> (select :*)
                       (from [:article_criteria :ac2])
                       (where [:and
                               [:= :ac2.article_id :a.article_id]
                               [:= :ac2.user_id self-id]
                               [:!= :ac2.answer nil]]))]]])
        (order-by [(sql/call :max :lp.val) :desc])
        (#(if n-max (limit % n-max) (identity %)))
        do-query)))

(defn get-confirmed-labels []
  (->> (-> (select :article_id :answer)
           (from [:article_criteria :ac])
           (join [:criteria :c] [:= :c.criteria_id :ac.criteria_id])
           (where [:and
                   [:= :c.name "overall include"]
                   [:!= :confirm_time nil]])
           do-query)
       (group-by :article_id)
       (map-values (fn [xs]
                     (let [yes-count (->> xs (filter #(true? (:answer %))) count)
                           no-count (->> xs (filter #(false? (:answer %))) count)
                           include? (cond (> yes-count no-count) true
                                          (< yes-count no-count) false
                                          :else nil)]
                       {:answer include?})))))
