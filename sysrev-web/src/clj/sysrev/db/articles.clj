(ns sysrev.db.articles
  (:require
   [clojure.java.jdbc :as j]
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [active-db do-query do-execute do-transaction]]
   [sysrev.predict.core :refer [latest-predict-run]]
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

(defn random-unlabeled-article [predict-run-id]
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
                [:= :lp.predict_run_id predict-run-id]
                [:= :c.name "overall include"]
                [:= :lp.stage 1]])
        do-query
        first)))

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
  [predict-run-id self-id n-max & [above-score]]
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
                [:= :lp.predict_run_id predict-run-id]
                [:= :lp.criteria_id :c.criteria_id]
                [:= :lp.stage 1]
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
  [predict-run-id self-id n-max & [above-score]]
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
                [:= :lp.predict_run_id predict-run-id]
                [:= :lp.criteria_id :c.criteria_id]
                [:= :lp.stage 1]
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

(defn fix-duplicate-authors-entries [project-id]
  (let [conn (j/get-connection @active-db)]
    (->>
     (-> (select :article_id :authors)
         (from :article)
         (where [:= :project_id project-id])
         do-query)
     (pmap
      (fn [a]
        (let [authors (:authors a)
              distinct-authors (vec (distinct authors))]
          (when (< (count distinct-authors) (count authors))
            (println (format "fixing authors field for article #%d"
                             (:article_id a)))
            (println (pr-str authors))
            (println (pr-str distinct-authors))
            (let [sql-authors
                  (.createArrayOf conn "text"
                                  (into-array String distinct-authors))]
              (-> (sqlh/update :article)
                  (sset {:authors sql-authors})
                  (where [:= :article_id (:article_id a)])
                  do-execute))))))
     doall)
    true))

(defn get-article [article-id & [predict-run-id]]
  (let [project-id 1
        predict-run-id
        (or predict-run-id
            (:predict_run_id (latest-predict-run project-id)))]
    (-> (select :a.* [:lp.val :score])
        (from [:article :a])
        (left-join [:label_predicts :lp] [:= :a.article_id :lp.article_id])
        (merge-left-join [:criteria :c] [:= :lp.criteria_id :c.criteria_id])
        (where [:and
                [:= :a.article_id article-id]
                [:or
                 [:= :lp.val nil]
                 [:and
                  [:= :c.name "overall include"]
                  [:= :lp.predict_run_id predict-run-id]
                  [:= :lp.stage 1]]]])
        do-query
        first)))
