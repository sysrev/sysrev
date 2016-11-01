(ns sysrev.db.articles
  (:require
   [clojure.java.jdbc :as j]
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [active-db do-query do-execute do-transaction to-sql-array]]
   [sysrev.predict.core :refer [latest-predict-run]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn label-confirmed-test [confirmed?]
  (case confirmed?
    true [:!= :confirm-time nil]
    false [:= :confirm-time nil]
    true))

(defn all-overall-labels []
  (->>
   (-> (select :a.article-id :ac.user-id :ac.answer)
       (from [:article :a])
       (join [:article-criteria :ac]
             [:= :ac.article-id :a.article-id])
       (merge-join [:criteria :c]
                   [:= :ac.criteria-id :c.criteria-id])
       (where [:and
               [:= :c.name "overall include"]
               [:!= :ac.confirm-time nil]
               [:!= :ac.answer nil]])
       do-query)
   (group-by :article-id)))

(defn all-label-conflicts []
  (->> (all-overall-labels)
       (filter (fn [[aid labels]]
                 (< 1 (->> labels (map :answer) distinct count))))
       (apply concat)
       (apply hash-map)))

(defn all-criteria []
  (-> (select :*)
      (from :criteria)
      (order-by :criteria-id)
      do-query))

(defn get-criteria-id [name]
  (-> (select :criteria-id)
      (from :criteria)
      (where [:= :name name])
      do-query
      first
      :criteria-id))

(defn alter-criteria [id values]
  (-> (sqlh/update :criteria)
      (sset values)
      (where [:= :criteria-id id])
      do-execute))

(defn random-unlabeled-article [predict-run-id]
  (let [article-ids
        (->>
         (-> (select :article-id)
             (from [:article :a])
             (where [:not
                     [:exists
                      (-> (select :*)
                          (from [:article-criteria :ac])
                          (where
                           [:and
                            [:= :ac.article-id :a.article-id]
                            [:!= :ac.answer nil]]))]])
             (limit 500)
             do-query)
         (map :article-id))
        random-id (nth article-ids (rand-int (count article-ids)))]
    (-> (select :a.* [:lp.val :score])
        (from [:article :a])
        (join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
        (merge-join [:criteria :c] [:= :lp.criteria-id :c.criteria-id])
        (where [:and
                [:= :a.article-id random-id]
                [:= :lp.predict-run-id predict-run-id]
                [:= :c.name "overall include"]
                [:= :lp.stage 1]])
        do-query
        first)))

(defn all-labels-for-article [article-id]
  (-> (select :ac.*)
      (from [:article-criteria :ac])
      (where [:= :ac.article-id article-id])
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
  [predict-run-id self-id n-max & [above-score]]
  (let [above-score (or above-score 1.5)]
    (-> (select :a.* [(sql/call :max :lp.val) :score])
        (from [:article :a])
        (join [:article-criteria :ac] [:= :ac.article-id :a.article-id])
        (merge-join [:criteria :c] [:= :c.criteria-id :ac.criteria-id])
        (merge-join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
        (where [:and
                [:= :c.name "overall include"]
                [:!= :ac.user-id self-id]
                [:!= :ac.answer nil]
                [:!= :ac.confirm-time nil]
                [:= :lp.predict-run-id predict-run-id]
                [:= :lp.criteria-id :c.criteria-id]
                [:= :lp.stage 1]
                [:< :lp.val above-score]])
        (group :a.article-id)
        (having [:and
                 ;; one user found with a confirmed inclusion label
                 [:= 1 (sql/call :count (sql/call :distinct :ac.user-id))]
                 ;; and `self-id` has not labeled the article
                 [:not
                  [:exists
                   (-> (select :*)
                       (from [:article-criteria :ac2])
                       (where [:and
                               [:= :ac2.article-id :a.article-id]
                               [:= :ac2.user-id self-id]
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
  [predict-run-id self-id n-max & [above-score]]
  (let [above-score (or above-score 1.5)]
    (-> (select :a.* [(sql/call :max :lp.val) :score])
        (from [:article :a])
        (join [:article-criteria :ac] [:= :ac.article-id :a.article-id])
        (merge-join [:criteria :c] [:= :c.criteria-id :ac.criteria-id])
        (merge-join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
        (where [:and
                [:= :c.name "overall include"]
                [:!= :ac.user-id self-id]
                [:!= :ac.answer nil]
                [:!= :ac.confirm-time nil]
                [:= :lp.predict-run-id predict-run-id]
                [:= :lp.criteria-id :c.criteria-id]
                [:= :lp.stage 1]
                [:< :lp.val above-score]])
        (group :a.article-id)
        (having [:and
                 ;; article has two differing inclusion labels
                 [:= 2 (sql/call :count (sql/call :distinct :ac.user-id))]
                 [:= 2 (sql/call :count (sql/call :distinct :ac.answer))]
                 ;; and `self-id` has not labeled the article
                 [:not
                  [:exists
                   (-> (select :*)
                       (from [:article-criteria :ac2])
                       (where [:and
                               [:= :ac2.article-id :a.article-id]
                               [:= :ac2.user-id self-id]
                               [:!= :ac2.answer nil]]))]]])
        (order-by [(sql/call :max :lp.val) :desc])
        (#(if n-max (limit % n-max) (identity %)))
        do-query)))

(defn fix-duplicate-authors-entries [project-id]
  (->>
   (-> (select :article-id :authors)
       (from :article)
       (where [:= :project-id project-id])
       do-query)
   (pmap
    (fn [a]
      (let [authors (:authors a)
            distinct-authors (vec (distinct authors))]
        (when (< (count distinct-authors) (count authors))
          (println (format "fixing authors field for article #%d"
                           (:article-id a)))
          (println (pr-str authors))
          (println (pr-str distinct-authors))
          (let [sql-authors
                (to-sql-array "text" distinct-authors)]
            (-> (sqlh/update :article)
                (sset {:authors sql-authors})
                (where [:= :article-id (:article-id a)])
                do-execute))))))
   doall)
  true)

(defn get-article [article-id & [predict-run-id]]
  (let [project-id 1
        predict-run-id
        (or predict-run-id
            (:predict-run-id (latest-predict-run project-id)))]
    (-> (select :a.* [:lp.val :score])
        (from [:article :a])
        (left-join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
        (merge-left-join [:criteria :c] [:= :lp.criteria-id :c.criteria-id])
        (where [:and
                [:= :a.article-id article-id]
                [:or
                 [:= :lp.val nil]
                 [:and
                  [:= :c.name "overall include"]
                  [:= :lp.predict-run-id predict-run-id]
                  [:= :lp.stage 1]]]])
        do-query
        first)))

;; This finds examples of highly-similar and highly-dissimilar articles
;; relative to `article-id`.
(defn find-similarity-examples-for-article [sim-version-id article-id]
  (let [good
        (->>
         (-> (select :*)
             (from :article-similarity)
             (where [:and
                     [:= :sim-version-id sim-version-id]
                     [:or
                      [:= :lo-id article-id]
                      [:= :hi-id article-id]]
                     [:!= :lo-id :hi-id]
                     [:> :similarity 0.01]])
             (order-by [:similarity :asc])
             (limit 5)
             do-query)
         reverse
         (map (fn [e]
                (let [other-id (if (= (:lo-id e) article-id)
                                 (:hi-id e)
                                 (:lo-id e))
                      article (-> (select :article-id
                                          :primary-title
                                          :secondary-title
                                          :abstract)
                                  (from :article)
                                  (where [:= :article-id other-id])
                                  do-query first)]
                  (assoc article :distance (:similarity e)))))
         (filter #(>= (count (:abstract %)) 200))
         first)
        bad
        (->>
         (-> (select :*)
             (from :article-similarity)
             (where [:and
                     [:= :sim-version-id sim-version-id]
                     [:or
                      [:= :lo-id article-id]
                      [:= :hi-id article-id]]
                     [:!= :lo-id :hi-id]
                     [:> :similarity 0.9]
                     [:< :similarity 0.98]])
             (order-by [:similarity :desc])
             (limit 100)
             do-query)
         reverse
         (map (fn [e]
                (let [other-id (if (= (:lo-id e) article-id)
                                 (:hi-id e)
                                 (:lo-id e))
                      article (-> (select :article-id
                                          :primary-title
                                          :secondary-title
                                          :abstract)
                                  (from :article)
                                  (where [:= :article-id other-id])
                                  do-query first)]
                  (assoc article :distance (:similarity e)))))
         (filter #(>= (count (:abstract %)) 200))
         first)
        article (-> (select :article-id
                            :primary-title
                            :secondary-title
                            :abstract)
                    (from :article)
                    (where [:= :article-id article-id])
                    do-query first)]
    {:article article
     :similar good
     :unsimilar bad}))
