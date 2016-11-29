(ns sysrev.db.labels
  (:require [sysrev.db.core :refer
             [do-query do-execute do-transaction sql-now
              to-sql-array to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :refer [project-overall-cid]]
            [sysrev.predict.core :refer [latest-predict-run-id]]
            [sysrev.util :refer [in? map-values]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.math.numeric-tower :as math]))

(def valid-label-categories
  ["inclusion criteria" "extra"])
(def valid-label-value-types
  ["boolean"])

(defn add-label-entry
  [project-id {:keys [name question short-label
                      category required value-type definition]}]
  (assert (in? valid-label-categories category))
  (assert (in? valid-label-value-types value-type))
  (do-transaction
   nil
   (let [next-project-ordering
         (->> (q/query-project-labels project-id [:project-ordering])
              (mapv :project-ordering)
              (apply max -1)
              inc)]
     (-> (insert-into :label)
         (values [{:project-id project-id
                   :project-ordering next-project-ordering
                   :value-type value-type
                   :name name
                   :question question
                   :short-label short-label
                   :required required
                   :category category
                   :definition (to-jsonb definition)
                   :enabled true}])
         do-execute))))

(defn add-label-entry-boolean
  [project-id {:keys [name question short-label
                      inclusion-value required custom-category]
               :as entry-values}]
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required]
         (select-keys entry-values))
    {:category (or custom-category
                   (if (nil? inclusion-value)
                     "extra" "inclusion criteria"))
     :value-type "boolean"
     :definition (if (nil? inclusion-value)
                   nil
                   {:inclusion-values [inclusion-value]})})))

(defn ideal-unlabeled-article [project-id & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (latest-predict-run-id project-id))
        articles
        (->>
         (-> (select :a.article-id [:lp.val :score])
             (from [:article :a])
             (join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
             (merge-join [:criteria :c] [:= :lp.criteria-id :c.criteria-id])
             (where
              [:and
               [:= :a.project-id project-id]
               [:= :lp.predict-run-id predict-run-id]
               [:= :c.name "overall include"]
               [:= :lp.stage 1]
               [:not
                [:exists
                 (-> (select :*)
                     (from [:article-criteria :ac])
                     (where
                      [:and
                       [:= :ac.article-id :a.article-id]
                       [:!= :ac.answer nil]]))]]])
             ;; (order-by [:lp.val :asc])
             do-query))
        _ (assert (= (->> articles (mapv :article-id) distinct count)
                     (count articles)))
        n-closest (max 5 (quot (count articles) 20))]
    (when-let [article-id
               (->> articles
                    (sort-by #(math/abs (- (:score %) 0.5)) <)
                    (take n-closest)
                    (#(when-not (empty? %) (rand-nth %)))
                    :article-id)]
      (let [[article locations]
            (pvalues
             (-> (select :a.* [:lp.val :score])
                 (from [:article :a])
                 (join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
                 (merge-join [:criteria :c] [:= :lp.criteria-id :c.criteria-id])
                 (where [:and
                         [:= :a.article-id article-id]
                         [:= :lp.predict-run-id predict-run-id]
                         [:= :c.name "overall include"]
                         [:= :lp.stage 1]])
                 do-query
                 first)
             (->> (-> (select :source :external_id)
                      (from [:article-location :al])
                      (where [:= :al.article-id article-id])
                      do-query)
                  (group-by :source)))]
        (assoc article :locations locations)))))

(defn ideal-single-labeled-article
  "The purpose of this function is to find articles that have a confirmed
  inclusion label from exactly one user that is not `self-id`, to present
  to `self-id` to label the article a second time.

  Articles which have any labels saved by `self-id` (even unconfirmed) will
  be excluded from this query."
  [project-id self-id & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (latest-predict-run-id project-id))
        articles
        (-> (select :a.article-id [(sql/call :max :lp.val) :score])
            (from [:article :a])
            (join [:article-criteria :ac] [:= :ac.article-id :a.article-id])
            (merge-join [:criteria :c] [:= :c.criteria-id :ac.criteria-id])
            (merge-join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
            (where [:and
                    [:= :a.project-id project-id]
                    [:= :c.name "overall include"]
                    [:!= :ac.user-id self-id]
                    [:!= :ac.answer nil]
                    [:!= :ac.confirm-time nil]
                    [:= :lp.predict-run-id predict-run-id]
                    [:= :lp.criteria-id :c.criteria-id]
                    [:= :lp.stage 1]])
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
            do-query)
        _ (assert (= (->> articles (mapv :article-id) distinct count)
                     (count articles)))
        n-closest (max 5 (quot (count articles) 20))]
    (when-let [article-id
               (->> articles
                    (sort-by #(math/abs (- (:score %) 0.5)) <)
                    (take n-closest)
                    (#(when-not (empty? %) (rand-nth %)))
                    :article-id)]
      (let [[article locations]
            (pvalues
             (-> (select :a.* [:lp.val :score])
                 (from [:article :a])
                 (join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
                 (merge-join [:criteria :c] [:= :lp.criteria-id :c.criteria-id])
                 (where [:and
                         [:= :a.article-id article-id]
                         [:= :lp.predict-run-id predict-run-id]
                         [:= :c.name "overall include"]
                         [:= :lp.stage 1]])
                 do-query
                 first)
             (->> (-> (select :source :external_id)
                      (from [:article-location :al])
                      (where [:= :al.article-id article-id])
                      do-query)
                  (group-by :source)))]
        (assoc article :locations locations)))))

(defn get-conflict-articles
  "The purpose of this function is to find articles with conflicting labels,
  to present to user `self-id` to resolve the conflict by labeling. These are
  the first priority in the classify queue.

  Queries for articles with conflicting confirmed inclusion labels from two
  users who are not `self-id`, and for which the article has no labels saved
  by `self-id` (even unconfirmed)."
  [project-id self-id n-max & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (latest-predict-run-id project-id))]
    (-> (select :a.* [(sql/call :max :lp.val) :score])
        (from [:article :a])
        (join [:article-criteria :ac] [:= :ac.article-id :a.article-id])
        (merge-join [:criteria :c] [:= :c.criteria-id :ac.criteria-id])
        (merge-join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
        (where [:and
                [:= :a.project-id project-id]
                [:= :c.name "overall include"]
                [:!= :ac.user-id self-id]
                [:!= :ac.answer nil]
                [:!= :ac.confirm-time nil]
                [:= :lp.predict-run-id predict-run-id]
                [:= :lp.criteria-id :c.criteria-id]
                [:= :lp.stage 1]])
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
        (order-by :a.article-id)
        (#(if n-max (limit % n-max) (identity %)))
        do-query)))

(defn get-user-label-task [project-id user-id]
  (let [[pending unlabeled]
        (pvalues
         (ideal-single-labeled-article project-id user-id)
         (ideal-unlabeled-article project-id))
        [article status]
        (cond
          (and pending unlabeled)
          (if (<= (rand) 0.75) [unlabeled :fresh] [pending :single])
          pending [pending :single]
          unlabeled [unlabeled :fresh]
          :else nil)]
    (when (and article status)
      (-> article
          (assoc :review-status status)
          (dissoc :raw)))))

(defn all-labels-for-article [article-id]
  (-> (select :ac.*)
      (from [:article-criteria :ac])
      (where [:= :ac.article-id article-id])
      do-query))

(defn article-user-labels-map [article-id]
  (let [labels (all-labels-for-article article-id)
        user-ids (->> labels (map :user-id) distinct)]
    (->> user-ids
         (map (fn [user-id]
                [user-id
                 (->> labels
                      (filter #(= (:user-id %) user-id))
                      (map #(do [(:criteria-id %)
                                 (:answer %)]))
                      (apply concat)
                      (apply hash-map))]))
         (apply concat)
         (apply hash-map))))

(defn get-user-article-labels [user-id article-id]
  (->> (-> (select :criteria-id :answer)
           (from :article-criteria)
           (where [:and
                   [:= :article-id article-id]
                   [:= :user-id user-id]])
           do-query)
       (map #(do [(:criteria-id %) (:answer %)]))
       (apply concat)
       (apply hash-map)))

(defn user-article-confirmed? [user-id article-id]
  (assert (and (integer? user-id) (integer? article-id)))
  (-> (select :%count.*)
      (from :article-criteria)
      (where [:and
              [:= :user-id user-id]
              [:= :article-id article-id]
              [:!= :confirm-time nil]])
      do-query first :count pos?))

(defn set-user-article-labels [user-id article-id label-values imported?]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (do-transaction
   nil
   (let [[now project-id]
         (pvalues
          (sql-now)
          (-> (select :project-id)
              (from :article)
              (where [:= :article-id article-id])
              do-query first :project-id))
         overall-cid (project-overall-cid project-id)
         confirm? (and imported?
                       ((comp not nil?)
                        (get label-values overall-cid)))
         existing-cids
         (->> (-> (select :criteria-id)
                  (from :article-criteria)
                  (where [:and
                          [:= :article-id article-id]
                          [:= :user-id user-id]])
                  do-query)
              (map :criteria-id))
         new-cids
         (->> (keys label-values)
              (remove (in? existing-cids)))
         new-entries
         (->> new-cids (map (fn [cid]
                              {:criteria-id cid
                               :article-id article-id
                               :user-id user-id
                               :answer (get label-values cid)
                               :confirm-time (if confirm? now nil)
                               :imported imported?})))]
     (doseq [cid existing-cids]
       (-> (sqlh/update :article-criteria)
           (sset {:answer (get label-values cid)
                  :updated-time now
                  :imported imported?})
           (where [:and
                   [:= :article-id article-id]
                   [:= :user-id user-id]
                   [:= :criteria-id cid]
                   [:or imported? [:= :confirm-time nil]]])
           do-execute))
     (when-not (empty? new-entries)
       (-> (insert-into :article-criteria)
           (values new-entries)
           do-execute))
     true)))

(defn confirm-user-article-labels
  "Mark all labels by `user-id` on `article-id` as being confirmed at current time."
  [user-id article-id]
  (assert (not (user-article-confirmed? user-id article-id)))
  (do-transaction
   nil
   (let [required (-> (select :answer)
                      (from [:article-criteria :ac])
                      (join [:criteria :c]
                            [:= :ac.criteria-id :c.criteria-id])
                      (where [:and
                              [:= :user-id user-id]
                              [:= :article-id article-id]
                              [:= :c.name "overall include"]])
                      do-query)]
     (assert (not (empty? required)))
     (assert (every? (comp not nil? :answer) required)))
   (-> (sqlh/update :article-criteria)
       (sset {:confirm-time (sql-now)})
       (where [:and
               [:= :user-id user-id]
               [:= :article-id article-id]])
       do-execute)))

(defn confirm-user-labels
  "Mark all labels by `user-id` as being confirmed at current time,
  for articles where values for the required labels are set."
  [project-id user-id]
  (do-transaction
   nil
   (-> (sqlh/update [:article-criteria :ac1])
       (join [:article :a1] [:= :a1.article-id :ac1.article-id])
       (sset {:confirm-time (sql-now)})
       (where [:and
               [:= :a1.project-id project-id]
               [:= :ac1.user-id user-id]
               [:= :ac1.confirm-time nil]
               [:exists
                (-> (select :*)
                    (from [:article-criteria :ac2])
                    (join [:criteria :c] [:= :c.criteria-id :ac2.criteria-id])
                    (where [:and
                            [:= :ac1.article-id :ac2.article-id]
                            [:= :ac2.user-id user-id]
                            [:= :c.name "overall include"]
                            [:!= :ac2.answer nil]]))]])
       do-execute)))
