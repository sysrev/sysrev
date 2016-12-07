(ns sysrev.db.labels
  (:require [sysrev.db.core :refer
             [do-query do-query-map do-execute do-transaction
              sql-now to-sql-array to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :refer [project-labels project-overall-label-id]]
            [sysrev.util :refer [in? map-values crypto-rand crypto-rand-nth]]
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
   (-> (insert-into :label)
       (values [{:project-id project-id
                 :project-ordering (q/next-label-project-ordering project-id)
                 :value-type value-type
                 :name name
                 :question question
                 :short-label short-label
                 :required required
                 :category category
                 :definition (to-jsonb definition)
                 :enabled true}])
       do-execute)))

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

(defn unlabeled-articles [project-id & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))
        articles
        (->>
         (-> (q/select-project-articles project-id [:a.article-id])
             (q/with-article-predict-score predict-run-id)
             (merge-where
              [:not
               [:exists
                (-> (q/select-article-by-id
                     :a.article-id
                     [:a2.article-id]
                     {:tname :a2
                      :project-id project-id})
                    (q/join-article-labels {:tname-a :a2})
                    (q/filter-valid-article-label nil))]])
             do-query))
        _ (assert (= (->> articles (mapv :article-id) distinct count)
                     (count articles)))]
    articles))

(defn single-labeled-articles [project-id self-id & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))
        articles
        (-> (q/select-project-articles
             project-id [:a.article-id [(sql/call :max :lp.val) :score]])
            (q/join-article-labels)
            (q/join-article-label-defs)
            (q/filter-overall-label)
            (q/filter-valid-article-label true)
            (q/join-article-predict-values predict-run-id 1)
            (merge-where
             [:and
              [:= :lp.label-id :l.label-id]
              [:!= :al.user-id self-id]])
            (group :a.article-id)
            (having
             [:and
              ;; one user found with a confirmed inclusion label
              [:= 1 (sql/call :count (sql/call :distinct :al.user-id))]
              ;; and `self-id` has not labeled the article
              [:not
               [:exists
                (-> (select :*)
                    (from [:article-label :al2])
                    (where [:and
                            [:= :al2.article-id :a.article-id]
                            [:= :al2.user-id self-id]
                            [:!= :al2.answer nil]]))]]])
            do-query)
        _ (assert (= (->> articles (mapv :article-id) distinct count)
                     (count articles)))]
    articles))

(defn- pick-ideal-article
  "Used by the classify task functions to select an article from the candidates.

  Randomly picks from the top 5% of article entries sorted by `sort-keyfn`."
  [articles sort-keyfn & [predict-run-id]]
  (let [n-closest (max 5 (quot (count articles) 20))]
    (when-let [article-id
               (->> articles
                    (sort-by sort-keyfn <)
                    (take n-closest)
                    (#(when-not (empty? %) (crypto-rand-nth %)))
                    :article-id)]
      (-> (q/query-article-by-id-full
           article-id {:predict-run-id predict-run-id})
          (dissoc :raw)))))

(defn ideal-unlabeled-article
  "Selects an unlabeled article to assign to a user for classification."
  [project-id & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (unlabeled-articles project-id predict-run-id)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn ideal-single-labeled-article
  "The purpose of this function is to find articles that have a confirmed
  inclusion label from exactly one user that is not `self-id`, to present
  to `self-id` to label the article a second time.

  Articles which have any labels saved by `self-id` (even unconfirmed) will
  be excluded from this query."
  [project-id self-id & [predict-run-id]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (single-labeled-articles project-id self-id predict-run-id)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

#_
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
          (if (<= (crypto-rand) 0.75) [unlabeled :fresh] [pending :single])
          pending [pending :single]
          unlabeled [unlabeled :fresh]
          :else nil)]
    (when (and article status)
      (-> article
          (assoc :review-status status)
          (dissoc :raw)))))

(defn article-user-labels-map [article-id]
  (->>
   (-> (q/select-article-by-id article-id [:al.*])
       (q/join-article-labels)
       do-query)
   (group-by :user-id)
   (map-values
    #(->> %
          (group-by :label-id)
          (map-values first)
          (map-values :answer)))))

(defn get-user-article-labels [user-id article-id]
  (->>
   (-> (q/select-article-by-id
        article-id [:al.label-id :al.answer])
       (q/join-article-labels)
       (q/filter-label-user user-id)
       (q/filter-valid-article-label nil)
       do-query)
   (group-by :label-id)
   (map-values first)
   (map-values :answer)))

(defn user-article-confirmed? [user-id article-id]
  (assert (and (integer? user-id) (integer? article-id)))
  (-> (q/select-article-by-id article-id [:%count.*])
      (q/join-article-labels)
      (q/filter-label-user user-id)
      (merge-where [:!= :al.confirm-time nil])
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
          (:project-id (q/query-article-by-id article-id [:project-id])))
         overall-label-id (project-overall-label-id project-id)
         confirm? (and imported?
                       ((comp not nil?)
                        (get label-values overall-label-id)))
         existing-label-ids
         (-> (q/select-article-by-id article-id [:al.label-id])
             (q/join-article-labels)
             (q/filter-label-user user-id)
             (do-query-map :label-id))
         new-label-ids
         (->> (keys label-values)
              (remove (in? existing-label-ids)))
         new-entries
         (->> new-label-ids
              (map (fn [label-id]
                     {:label-id label-id
                      :article-id article-id
                      :user-id user-id
                      :answer (to-jsonb (get label-values label-id))
                      :confirm-time (if confirm? now nil)
                      :imported imported?})))]
     (doseq [label-id existing-label-ids]
       (-> (sqlh/update :article-label)
           (sset {:answer (to-jsonb (get label-values label-id))
                  :updated-time now
                  :imported imported?})
           (where [:and
                   [:= :article-id article-id]
                   [:= :user-id user-id]
                   [:= :label-id label-id]
                   [:or imported? [:= :confirm-time nil]]])
           do-execute))
     (when-not (empty? new-entries)
       (-> (insert-into :article-label)
           (values new-entries)
           do-execute))
     true)))

(defn confirm-user-article-labels
  "Mark all labels by `user-id` on `article-id` as being confirmed at current time."
  [user-id article-id]
  (assert (not (user-article-confirmed? user-id article-id)))
  (do-transaction
   nil
   ;; TODO - does this check that all required labels are set?
   (let [required (-> (q/select-article-by-id article-id [:al.answer])
                      (q/join-article-labels)
                      (q/join-article-label-defs)
                      (q/filter-label-user user-id)
                      (merge-where [:= :l.required true])
                      (do-query-map :answer))]
     (assert ((comp not empty?) required))
     (assert (every? (comp not nil?) required)))
   (-> (sqlh/update :article-label)
       (sset {:confirm-time (sql-now)})
       (where [:and
               [:= :user-id user-id]
               [:= :article-id article-id]])
       do-execute)))
