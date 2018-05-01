(ns sysrev.db.labels
  (:require [bouncer.validators :as v]
            [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.db.core :as db :refer
             [do-query do-query-map do-execute with-transaction
              sql-now to-sql-array to-jsonb sql-cast
              with-query-cache with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.db.articles :refer [query-article-by-id-full]]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.shared.labels :refer [cleanup-label-answer]]
            [sysrev.shared.article-list :refer
             [is-resolved? is-conflict? is-single? is-consistent?]]
            [sysrev.util :refer [crypto-rand crypto-rand-nth]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.math.numeric-tower :as math]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clojure.string :as str])
  (:import java.util.UUID))

(def valid-label-categories
  ["inclusion criteria" "extra"])
(def valid-label-value-types
  ["boolean" "categorical" "numeric" "string"])
(def valid-numeric-types
  ["integer" "percent" "ratio" "real"])

(defn all-labels-cached []
  (with-query-cache [:all-labels]
    (->>
     (-> (q/select-label-where nil true [:*])
         do-query)
     (group-by :label-id)
     (map-values first))))

(defn delete-label-entry [project-id label-id]
  (let [label (-> (q/select-label-where
                   project-id [:= :label-id label-id] [:*])
                  do-query first)]
    (if (nil? label)
      false
      (do (-> (delete-from :label)
              (where [:and
                      [:= :label-id label-id]
                      [:= :project-id project-id]])
              do-execute)
          true))))

(defn add-label-entry
  "Creates an entry for a label definition.

  Ordinarily this will be directly called only by one of the type-specific 
  label creation functions."
  [project-id {:keys [name question short-label
                      category required value-type definition]}]
  (assert (in? valid-label-categories category))
  (assert (in? valid-label-value-types value-type))
  (try
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
        do-execute)
    (finally
      (db/clear-project-cache project-id)))
  true)

(defn add-label-entry-boolean
  "Creates an entry for a boolean label definition.

  `name` `question` `short-label` are strings describing the label.

  `inclusion-value` may be `true` or `false` to set that value as required
  for overall inclusion, or may be `nil` for no inclusion requirement.

  `required` is `true` or `false` to determine if this label must be set for an  article

  `custom-category` is optional, unless specified the label category will be
  determined from the value of `inclusion-value`."
  [project-id
   {:keys [name question short-label inclusion-value required
           custom-category]
    :as entry-values}]
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required]
         (select-keys entry-values))
    {:value-type "boolean"
     :category (or custom-category
                   (if (nil? inclusion-value)
                     "extra" "inclusion criteria"))
     :definition (if (nil? inclusion-value)
                   nil
                   {:inclusion-values [inclusion-value]})})))

(defn add-label-entry-categorical
  "Creates an entry for a categorical label definition.

  `name` `question` `short-label` are strings describing the label.

  `all-values` are the values from which to choose from

  `inclusion-values` should be a sequence of the values that are acceptable
  for inclusion. If `inclusion-values` is empty, answers for this label
  are treated as having no relationship to inclusion. If `inclusion-values` is
  not empty, answers are treated as implying inclusion if any of the answer
  values is present in `inclusion-values`; non-empty answers for which none of
  the values is present in `inclusion-values` are treated as implying 
  exclusion.

  `custom-category` is optional, unless specified the label category will be
  determined from the value of `inclusion-value`."
  [project-id
   {:keys [name question short-label all-values inclusion-values
           required multi? custom-category]
    :as entry-values}]
  (assert (sequential? all-values))
  (assert (sequential? inclusion-values))
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required]
         (select-keys entry-values))
    {:value-type "categorical"
     :category (or custom-category
                   (if (empty? inclusion-values)
                     "extra" "inclusion criteria"))
     :definition {:all-values all-values
                  :inclusion-values inclusion-values
                  :multi? (boolean multi?)}})))

(defn add-label-overall-include [project-id]
  (add-label-entry-boolean
   project-id {:name "overall include"
               :question "Include this article?"
               :short-label "Include"
               :inclusion-value true
               :required true}))

(defn define-numeric-label-unit
  [name numeric-type & [{:keys [min-bound max-bound]}]]
  (let [[min-val min-inclusive?] min-bound
        [max-val max-inclusive?] max-bound]
    (assert (or (nil? name) (string? name)))
    (assert (in? valid-numeric-types numeric-type))
    (assert (= (nil? min-val) (nil? min-inclusive?)))
    (assert (= (nil? max-val) (nil? max-inclusive?)))
    (when (= numeric-type "integer")
      (assert (or (nil? min-val) (integer? min-val)))
      (assert (or (nil? max-val) (integer? max-val))))
    (assert (in? [nil "inclusive" "exclusive"] min-inclusive?))
    (assert (in? [nil "inclusive" "exclusive"] max-inclusive?))
    (cond->
        {:name name :numeric-type numeric-type
         :unit-id (UUID/randomUUID)}
      min-bound (assoc :min-bound min-bound)
      max-bound (assoc :max-bound max-bound))))

;; *TODO*
;; finish adding numeric label type
(defn add-label-entry-numeric
  "Creates an entry for a numeric label definition, with one or more choices
  of unit selectable by user when providing answer.

  `integer-only?` if true will disallow non-integer values as answer."
  [project-id
   {:keys [name question short-label required custom-category units]
    :as entry-values}]
  (assert (sequential? units))
  (assert (not= 0 (count units)))
  (assert (every? (some-fn nil? string?) (map :name units)))
  (assert (in? [0 1] (->> units (map :name) (filter nil?) count)))
  (assert (every? map? units))
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required]
         (select-keys entry-values))
    {:value-type "numeric"
     :category (or custom-category "extra")
     :definition {:units units}})))

(defn add-label-entry-string
  "Creates an entry for a string label definition. Value is provided by user
  in a text input field.

  `name` `question` `short-label` are strings describing the label.
  `max-length` is a required integer.
  `regex` is an optional vector of strings to require that answers must match
  one of the regex values.
  `entity` is an optional string to identify what the value represents.
  `examples` is an optional list of example strings to indicate to users
  the required format.

  `required` is `true` or `false` to determine if this label must be set for an  article

  `multi?` if true allows multiple string values in answer."
  [project-id
   {:keys [name question short-label required custom-category
           max-length regex entity examples multi?]
    :as entry-values}]
  (assert (= (type multi?) Boolean))
  (assert (integer? max-length))
  (assert (or (nil? regex)
              (and (coll? regex)
                   (every? string? regex))))
  (assert (or (nil? examples)
              (and (coll? examples)
                   (every? string? examples))))
  (assert ((some-fn nil? string?) entity))
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required]
         (select-keys entry-values))
    {:value-type "string"
     :category (or custom-category "extra")
     :definition (cond->
                     {:multi? multi?
                      :max-length max-length}
                   regex (assoc :regex regex)
                   entity (assoc :entity entity)
                   examples (assoc :examples examples))})))

(defn alter-label-entry [project-id label-id values-map]
  (let [project-id (q/to-project-id project-id)
        label-id (q/to-label-id label-id)]
    (-> (sqlh/update :label)
        (sset (dissoc values-map :label-id :project-id))
        (where [:and
                [:= :label-id label-id]
                [:= :project-id project-id]])
        do-execute)
    (db/clear-project-cache project-id)))

(defn get-articles-with-label-users [project-id & [predict-run-id]]
  (with-project-cache
    project-id [:label-values :saved :articles predict-run-id]
    (let [predict-run-id
          (or predict-run-id (q/project-latest-predict-run-id project-id))
          articles
          (-> (q/select-project-articles project-id [:a.article-id])
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (-> (first x)
                          (merge {:users [] :score 0.0}))))))
          scores
          (-> (q/select-project-articles project-id [:a.article-id])
              (q/with-article-predict-score predict-run-id)
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (let [score (or (-> x first :score) 0.0)]
                        {:score score})))))
          labels
          (-> (q/select-project-articles
               project-id [:a.article-id :al.user-id :al.confirm-time])
              (q/join-article-labels)
              (q/join-article-label-defs)
              (q/filter-valid-article-label nil)
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (let [user-ids (->> x (map :user-id) distinct vec)
                            users-confirmed
                            (->> x (remove #(nil? (:confirm-time %)))
                                 (map :user-id) distinct vec)]
                        {:users user-ids
                         :users-confirmed users-confirmed})))))]
      (merge-with merge articles scores labels))))

(defn unlabeled-articles [project-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :unlabeled-articles predict-run-id]
    (->> (or articles (get-articles-with-label-users project-id predict-run-id))
         vals
         (filter #(= 0 (count (:users-confirmed %))))
         (map #(dissoc % :users-confirmed)))))

(defn single-labeled-articles [project-id self-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :single-labeled-articles self-id predict-run-id]
    (->> (or articles (get-articles-with-label-users project-id predict-run-id))
         vals
         (filter #(and (= 1 (count (:users %)))
                       (= 1 (count (:users-confirmed %)))
                       (not (in? (:users %) self-id))))
         (map #(dissoc % :users)))))

(defn fallback-articles [project-id self-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :fallback-articles self-id predict-run-id]
    (->> (or articles (get-articles-with-label-users project-id predict-run-id))
         vals
         (filter #(and (< (count (:users-confirmed %)) 2)
                       (not (in? (:users %) self-id))))
         (map #(dissoc % :users)))))

(defn- pick-ideal-article
  "Used by the classify task functions to select an article from the candidates.

  Randomly picks from the top 5% of article entries sorted by `sort-keyfn`."
  [articles sort-keyfn & [predict-run-id]]
  (let [n-closest (max 100 (quot (count articles) 20))]
    (when-let [article-id
               (->> articles
                    (sort-by sort-keyfn <)
                    (take n-closest)
                    (#(when-not (empty? %) (crypto-rand-nth %)))
                    :article-id)]
      (-> (query-article-by-id-full
           article-id {:predict-run-id predict-run-id})
          (dissoc :raw)))))

(defn ideal-unlabeled-article
  "Selects an unlabeled article to assign to a user for classification."
  [project-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (unlabeled-articles project-id predict-run-id articles)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn ideal-single-labeled-article
  "The purpose of this function is to find articles that have a confirmed
  inclusion label from exactly one user that is not `self-id`, to present
  to `self-id` to label the article a second time.

  Articles which have any labels saved by `self-id` (even unconfirmed) will
  be excluded from this query."
  [project-id self-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (single-labeled-articles project-id self-id predict-run-id articles)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn ideal-fallback-article
  "Selects a fallback (with unconfirmed labels) article to assign to a user."
  [project-id self-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (fallback-articles project-id self-id predict-run-id articles)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn user-confirmed-today-count [project-id user-id]
  (-> (q/select-project-article-labels project-id true [:al.article-id])
      (q/filter-label-user user-id)
      (merge-where [:=
                    (sql/call :date_trunc "day" :al.confirm-time)
                    (sql/call :date_trunc "day" :%now)])
      (->> do-query (map :article-id) distinct count)))

(defn get-user-label-task [project-id user-id]
  (let [{:keys [second-review-prob]
         :or {second-review-prob 0.5}} (project/project-settings project-id)
        articles (get-articles-with-label-users project-id)
        [pending unlabeled today-count]
        (pvalues
         (ideal-single-labeled-article project-id user-id nil articles)
         (ideal-unlabeled-article project-id nil articles)
         (user-confirmed-today-count project-id user-id))
        [article status]
        (cond
          (and pending unlabeled)
          (if (<= (crypto-rand) second-review-prob)
            [pending :single] [unlabeled :unreviewed])
          pending [pending :single]
          unlabeled [unlabeled :unreviewed]
          :else
          (when-let [fallback (ideal-fallback-article project-id user-id nil articles)]
            [fallback :single]))]
    (when (and article status)
      {:article-id (:article-id article)
       :today-count today-count})))

(defn- user-all-labels-map [project-id user-id]
  (-> (q/select-project-articles project-id [:al.*])
      (q/join-article-labels)
      (q/filter-label-user user-id)
      (->> do-query
           (group-by :article-id))))

(defn- copy-project-user-labels [project-id src-user-id dest-user-id]
  (let [articles (user-all-labels-map project-id src-user-id)]
    (with-transaction
      (doseq [[article-id article-entries] articles]
        (-> (delete-from [:article-label :al])
            (merge-where [:and
                          [:= :al.article-id article-id]
                          [:= :al.user-id dest-user-id]])
            do-execute)
        (doseq [entry article-entries]
          (let [new-entry (-> entry
                              (assoc :user-id dest-user-id)
                              (dissoc :article-label-local-id :article-label-id)
                              (update :answer to-jsonb))]
            (-> (insert-into :article-label)
                (values [new-entry])
                do-execute)))))))

(defn article-user-labels-map [project-id article-id]
  (->>
   (-> (q/select-article-by-id article-id [:al.*])
       (q/join-article-labels)
       do-query)
   (group-by :user-id)
   (map-values
    (fn [ulabels]
      (->> ulabels
           (group-by :label-id)
           (map-values first)
           (map-values
            (fn [{:keys [confirm-time updated-time] :as entry}]
              (merge (select-keys entry [:answer :resolve])
                     {:confirmed (not (nil? confirm-time))
                      :confirm-epoch
                      (if (nil? confirm-time) 0
                          (max (tc/to-epoch confirm-time)
                               (tc/to-epoch updated-time)))}))))))))

(defn merge-article-labels [article-ids]
  (let [labels
        (->> article-ids
             (mapv (fn [article-id]
                     (->>
                      (-> (q/select-article-by-id article-id [:al.*])
                          (q/join-article-labels)
                          do-query))))
             (apply concat)
             (group-by :user-id)
             (map-values #(group-by :article-id %))
             (map-values vec)
             (map-values #(sort-by (comp count second) > %)))]
    (doseq [[user-id ulabels] labels]
      (let [confirmed-labels
            (->> ulabels
                 (filter
                  (fn [[article-id alabels]]
                    (some #(not= (:confirm-time %) nil)
                          alabels))))
            keep-labels
            (cond (not (empty? confirmed-labels))
                  (second (first confirmed-labels))
                  :else
                  (second (first ulabels)))]
        (when (not (empty? keep-labels))
          (println
           (format "keeping %d labels for user=%s"
                   (count keep-labels) user-id))
          (with-transaction
            (doseq [article-id article-ids]
              (-> (delete-from :article-label)
                  (where [:and
                          [:= :article-id article-id]
                          [:= :user-id user-id]])
                  do-execute)
              (-> (insert-into :article-label)
                  (values
                   (->> keep-labels
                        (map
                         #(-> %
                              (assoc :article-id article-id)
                              (update :answer to-jsonb)
                              (dissoc :article-label-id)
                              (dissoc :article-label-local-id)))))
                  do-execute))))))
    true))

(defn get-user-article-labels [user-id article-id & [confirmed?]]
  (->>
   (-> (q/select-article-by-id
        article-id [:al.label-id :al.answer])
       (q/join-article-labels)
       (q/filter-label-user user-id)
       (q/filter-valid-article-label confirmed?)
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

(defn label-answer-valid? [label-id answer]
  (let [label (get (all-labels-cached) label-id)]
    (boolean
     (case (:value-type label)
       "boolean"
       (in? [true false nil] answer)
       "categorical"
       (cond (nil? answer)
             true
             (sequential? answer)
             (let [allowed (-> label :definition :all-values)]
               (every? (in? allowed) answer))
             :else false)
       ;; TODO check that answer value matches label regex
       "string" (and (not (nil? answer))
                     (coll? answer)
                     (every? string? answer)
                     (every? not-empty answer))
       true))))

(defn label-answer-inclusion [label-id answer]
  (let [label (get (all-labels-cached) label-id)
        ivals (-> label :definition :inclusion-values)]
    (case (:value-type label)
      "boolean"
      (cond
        (empty? ivals) nil
        (nil? answer) nil
        :else (boolean (in? ivals answer)))
      "categorical"
      (cond
        (empty? ivals) nil
        (nil? answer) nil
        (empty? answer) nil
        :else (boolean (some (in? ivals) answer)))
      nil)))

(defn label-possible-values [label-id]
  (let [label (get (all-labels-cached) label-id)]
    (case (:value-type label)
      "boolean"
      [true false]
      "categorical"
      (-> label :definition :all-values)
      nil)))

(defn filter-valid-label-values [label-values]
  (->> label-values
       (filterv
        (fn [[label-id answer]]
          (label-answer-valid? label-id answer)))
       (apply concat)
       (apply hash-map)))

;; TODO: check that all required labels are answered
(defn set-user-article-labels
  [user-id article-id label-values &
   {:keys [imported? confirm? change? resolve?]}]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (let [valid-values (->> label-values filter-valid-label-values)
        now (sql-now)
        project-id (:project-id (q/query-article-by-id article-id [:project-id]))
        current-entries
        (when change?
          (-> (q/select-article-by-id article-id [:al.*])
              (q/join-article-labels)
              (q/filter-label-user user-id)
              (->> (do-query)
                   (map #(-> %
                             (update :answer to-jsonb)
                             (update :imported boolean)
                             (dissoc :article-label-id :article-label-local-id))))))
        overall-label-id (project/project-overall-label-id project-id)
        confirm? (if imported?
                   (boolean (get valid-values overall-label-id))
                   confirm?)
        existing-label-ids
        (-> (q/select-article-by-id article-id [:al.label-id])
            (q/join-article-labels)
            (q/filter-label-user user-id)
            (->> do-query (map :label-id)))
        new-label-ids
        (->> (keys valid-values)
             (remove (in? existing-label-ids)))
        new-entries
        (->> new-label-ids
             (map (fn [label-id]
                    (let [label (get (all-labels-cached) label-id)
                          answer (->> (get valid-values label-id)
                                      (cleanup-label-answer label))
                          _ (assert (label-answer-valid? label-id answer))
                          inclusion (label-answer-inclusion label-id answer)]
                      (cond->
                          {:label-id label-id
                           :article-id article-id
                           :user-id user-id
                           :answer (to-jsonb answer)
                           :added-time now
                           :updated-time now
                           :imported (boolean imported?)
                           :resolve (boolean resolve?)
                           :inclusion inclusion}
                        confirm? (merge {:confirm-time now}))))))]
    (doseq [label-id existing-label-ids]
      (when (contains? valid-values label-id)
        (let [label (get (all-labels-cached) label-id)
              answer (->> (get valid-values label-id)
                          (cleanup-label-answer label))
              _ (assert (label-answer-valid? label-id answer))
              inclusion (label-answer-inclusion label-id answer)]
          (-> (sqlh/update :article-label)
              (sset (cond->
                        {:answer (to-jsonb answer)
                         :updated-time now
                         :imported (boolean imported?)
                         :resolve (boolean resolve?)
                         :inclusion inclusion}
                      confirm? (merge {:confirm-time now})))
              (where [:and
                      [:= :article-id article-id]
                      [:= :user-id user-id]
                      [:= :label-id label-id]])
              do-execute))))
    (when-not (empty? new-entries)
      (-> (insert-into :article-label)
          (values new-entries)
          do-execute))
    (when (and change? (not-empty current-entries))
      (-> (insert-into :article-label-history)
          (values current-entries)
          do-execute))
    (db/clear-project-cache project-id)
    true))

(defn delete-project-user-labels [project-id]
  (-> (delete-from [:article-label :al])
      (where [:exists
              (-> (select :*)
                  (from [:article :a])
                  (where [:and
                          [:= :a.article-id :al.article-id]
                          [:= :a.project-id project-id]]))])
      do-execute))

(defn update-label-answer-inclusion [label-id]
  (let [entries (-> (select :article-label-id :answer)
                    (from :article-label)
                    (where [:= :label-id label-id])
                    do-query)]
    (->> entries
         (map
          (fn [{:keys [article-label-id answer]}]
            (let [inclusion (label-answer-inclusion label-id answer)]
              (-> (sqlh/update :article-label)
                  (sset {:inclusion inclusion})
                  (where [:= :article-label-id article-label-id])
                  do-execute))))
         doall)))

(defn invert-boolean-label-answers [label-id]
  (let [true-ids
        (-> (select :article-label-id)
            (from :article-label)
            (where [:and
                    [:= :label-id label-id]
                    [:= :answer (to-jsonb true)]])
            (->> do-query (map :article-label-id)))
        false-ids
        (-> (select :article-label-id)
            (from :article-label)
            (where [:and
                    [:= :label-id label-id]
                    [:= :answer (to-jsonb false)]])
            (->> do-query (map :article-label-id)))]
    (doseq [true-id true-ids]
      (-> (sqlh/update :article-label)
          (sset {:answer (to-jsonb false)})
          (where [:= :article-label-id true-id])
          do-execute))
    (doseq [false-id false-ids]
      (-> (sqlh/update :article-label)
          (sset {:answer (to-jsonb true)})
          (where [:= :article-label-id false-id])
          do-execute))
    (update-label-answer-inclusion label-id)
    (log/info (format "inverted %d boolean answers"
                      (+ (count true-ids) (count false-ids))))
    true))

(defn set-label-enabled [label-id enabled?]
  (let [label-id (q/to-label-id label-id)]
    (-> (sqlh/update :label)
        (sset {:enabled enabled?})
        (where [:= :label-id label-id])
        do-execute)))
(s/fdef set-label-enabled
        :args (s/cat :label-id ::sc/label-id
                     :enabled? boolean?))

(defn set-label-required [label-id required?]
  (let [label-id (q/to-label-id label-id)]
    (-> (sqlh/update :label)
        (sset {:required required?})
        (where [:= :label-id label-id])
        do-execute)))
(s/fdef set-label-required
        :args (s/cat :label-id ::sc/label-id
                     :required? boolean?))

(defn query-public-article-labels [project-id]
  (with-project-cache
    project-id [:public-labels :values]
    (let [[all-articles all-labels]
          (pvalues
           (-> (select :a.article-id :a.primary-title)
               (from [:article :a])
               (where [:and
                       [:= :a.project-id project-id]
                       [:= :a.enabled true]
                       [:exists
                        (-> (select :*)
                            (from [:article-label :al])
                            (where [:and
                                    [:= :al.article-id :a.article-id]
                                    [:!= :al.confirm-time nil]
                                    [:!= :al.answer nil]]))]])
               (->> do-query
                    (group-by :article-id)
                    (map-values first)))
           (-> (select :a.article-id :l.label-id :al.answer :al.inclusion
                       :al.resolve :al.confirm-time :al.user-id)
               (from [:article :a])
               (join [:article-label :al] [:= :a.article_id :al.article_id]
                     [:label :l] [:= :al.label-id :l.label-id])
               (where [:and
                       [:= :a.project-id project-id]
                       [:= :a.enabled true]
                       [:!= :al.confirm-time nil]
                       [:!= :al.answer nil]])
               (->> (do-query)
                    (remove #(or (nil? (:answer %))
                                 (and (coll? (:answer %))
                                      (empty? (:answer %)))))
                    (map #(-> (assoc % :confirm-epoch
                                     (tc/to-epoch (:confirm-time %)))
                              (dissoc :confirm-time)))
                    (group-by :article-id)
                    (filter (fn [[article-id entries]]
                              (not-empty entries)))
                    (apply concat)
                    (apply hash-map)
                    (map-values (fn [entries]
                                  (map #(dissoc % :article-id) entries)))
                    (map-values (fn [entries]
                                  {:labels entries
                                   :updated-time (->> entries (map :confirm-epoch)
                                                      (apply max 0))})))))]
      (->> (keys all-labels)
           (filter #(contains? all-articles %))
           (map
            (fn [article-id]
              (let [article (get all-articles article-id)
                    updated-time (get-in all-labels [article-id :updated-time])
                    labels
                    (->> (get-in all-labels [article-id :labels])
                         (group-by :label-id)
                         (map-values
                          (fn [entries]
                            (map #(dissoc % :label-id :confirm-epoch) entries))))]
                [article-id {:title (:primary-title article)
                             :updated-time updated-time
                             :labels labels}])))
           (apply concat)
           (apply hash-map)))))

(defn query-progress-over-time [project-id n-days]
  (with-project-cache
    project-id [:public-labels :progress n-days]
    (let [overall-id (project/project-overall-label-id project-id)
          articles (->> (vals (query-public-article-labels project-id))
                        (filter
                         (fn [article]
                           (let [labels (get-in article [:labels overall-id])]
                             (and labels
                                  (or (is-consistent? labels)
                                      (is-resolved? labels)))))))
          now (tc/to-epoch (t/now))
          day-seconds (* 60 60 24)
          tformat (tf/formatters :year-month-day)]
      (->> (range 0 n-days)
           (mapv
            (fn [day-idx]
              (let [day-epoch (- now (* day-idx day-seconds))]
                {:day (tf/unparse tformat (tc/from-long (* 1000 day-epoch)))
                 :completed (->> articles
                                 (filter #(< (:updated-time %) day-epoch))
                                 count)})))))))

(defn filter-recent-public-articles [project-id exclude-hours articles]
  (if (nil? exclude-hours)
    articles
    (let [cutoff-epoch
          (tc/to-epoch (t/minus (tc/from-sql-date (sql-now))
                                (t/hours exclude-hours)))
          overall-id (project/project-overall-label-id project-id)]
      (->> (vec articles)
           (filter (fn [[article-id article]]
                     (let [labels (get-in article [:labels overall-id])]
                       (or (< (:updated-time article) cutoff-epoch)
                           (and labels (is-resolved? labels))))))
           (apply concat)
           (apply hash-map)))))

(defn query-member-articles [project-id user-id]
  (let [articles
        (-> (select :a.article-id :a.primary-title :al.answer :al.inclusion
                    :al.resolve :al.confirm-time :al.updated-time
                    :l.label-id :wu.user-id)
            (from [:article :a])
            (join [:project :p] [:= :p.project-id :a.project-id]
                  [:article-label :al] [:= :al.article-id :a.article-id]
                  [:label :l] [:= :l.label-id :al.label-id]
                  [:web-user :wu] [:= :wu.user-id :al.user-id])
            (where [:and
                    [:= :p.project-id project-id]
                    [:= :wu.user-id user-id]
                    [:= :a.enabled true]
                    [:= :l.enabled true]])
            (order-by :a.article-id)
            (->> (do-query)
                 (remove (fn [{:keys [answer]}]
                           (or (nil? answer)
                               (and (coll? answer) (empty? answer)))))
                 (map #(-> (assoc %
                                  :confirmed
                                  (not (nil? (:confirm-time %)))
                                  :updated-epoch
                                  (if (:confirm-time %)
                                    (some-> (:confirm-time %) (tc/to-epoch))
                                    (some-> (:updated-time %) (tc/to-epoch))))
                           (dissoc :confirm-time)))
                 (group-by :article-id)
                 (map-values
                  (fn [xs]
                    (let [primary-title (:primary-title (first xs))
                          confirmed (:confirmed (first xs))]
                      {:title primary-title
                       :confirmed confirmed
                       :updated-time (->> xs (map :updated-epoch) (remove nil?) (apply max 0))
                       :labels
                       (->> xs
                            (mapv #(dissoc % :primary-title :article-id
                                           :updated-time :updated-epoch :confirmed))
                            (group-by :label-id)
                            (map-values (fn [entries]
                                          (->> entries
                                               (mapv #(dissoc % :label-id))))))})))))
        all-article-ids (apply hash-set (keys articles))
        notes
        (-> (q/select-project-articles
             project-id [:a.article-id :an.content :pn.name])
            (q/with-article-note nil user-id)
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (filter #(some-> % :content (str/trim) (not-empty)))
                 (group-by :article-id)
                 (map-values
                  (fn [entries]
                    (->> entries
                         (group-by :name)
                         (map-values first)
                         (map-values :content)
                         (#(hash-map :notes %)))))))]
    (merge-with merge articles notes)))

(defn article-user-multi-labels
  "Queries article-label entries, returning a list entries for each user.
  Multiple entries should not exist, this is only useful for fixing them.
  (Also this is now prevented with a Postgres unique constraint)."
  [article-id]
  (->>
   (-> (q/select-article-by-id article-id [:al.*])
       (q/join-article-labels)
       do-query)
   (group-by :user-id)
   (map-values
    (fn [ulabels]
      (->> ulabels
           (group-by :label-id)
           (map-values
            (fn [entries]
              (->> entries (sort-by :article-label-local-id >)))))))))

(defn fix-duplicate-answers
  "Queries for any multiple entries in article-label for a single (article_id, user_id, label_id)
   and deletes all but the newest if they exist.
   Duplicates should be prevented now by a Postgres unique constraint, but this is needed
   for migration before the constraint can be added."
  []
  (let [entries
        (-> (q/select-project-articles nil [:al.article-id :al.user-id :al.label-id :al.answer])
            (q/join-article-labels)
            (->> do-query
                 (group-by (fn [{:keys [article-id user-id label-id]}]
                             [article-id user-id label-id]))
                 (filter (fn [[key answers]]
                           (> (count answers) 1)))
                 (apply concat)
                 (apply hash-map)))
        answers (->> entries (map-values #(map :answer %)))
        article-ids (->> entries vals (map #(map :article-id %)) (apply concat) distinct)]
    (when-not (empty? article-ids)
      (doseq [article-id article-ids]
        (when-let [multi-labels (article-user-multi-labels article-id)]
          (doseq [user-id (keys multi-labels)]
            (let [umap (get multi-labels user-id)]
              (doseq [label-id (keys umap)]
                (let [entries (get umap label-id)]
                  (when (> (count entries) 1)
                    (let [keep-id (->> entries (map :article-label-local-id) (apply max))]
                      (doseq [entry entries]
                        (when (not= keep-id (:article-label-local-id entry))
                          (let [deleted
                                (-> (delete-from :article-label)
                                    (where [:and
                                            [:= :article-id article-id]
                                            [:= :user-id user-id]
                                            [:= :label-id label-id]
                                            [:= :article-label-id (:article-label-id entry)]])
                                    do-execute)]
                            (println (str "deleted " deleted " from article-id=" article-id)))))))))))))
      (println (str "fixed duplicate answers for " (count article-ids) " articles")))
    true))

(defn project-included-articles [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (->> (vec articles)
           (filter
            (fn [[article-id article]]
              (let [labels (get-in article [:labels overall-id])
                    group-status
                    (cond (is-single? labels)     :single
                          (is-resolved? labels)   :resolved
                          (is-conflict? labels)   :conflict
                          :else                   :consistent)
                    inclusion-status
                    (case group-status
                      :conflict nil,
                      :resolved (->> labels (filter :resolve) (map :inclusion) first),
                      (->> labels (map :inclusion) first))]
                (and (in? [:consistent :resolved] group-status)
                     (true? inclusion-status)))))
           (apply concat)
           (apply hash-map)))))

(defn project-include-labels [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-labels]
    (->>
     (-> (q/select-project-article-labels
          project-id true
          [:a.article-id :al.user-id :al.answer :al.resolve])
         (q/filter-overall-label)
         do-query)
     (group-by :article-id))))

(defn project-include-label-conflicts [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-label-conflicts]
    (->> (project-include-labels project-id)
         (filter (fn [[aid labels]]
                   (< 1 (->> labels (map :answer) distinct count))))
         (filter (fn [[aid labels]]
                   (= 0 (->> labels (filter :resolve) count))))
         (apply concat)
         (apply hash-map))))

(defn project-include-label-resolved [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-label-resolved]
    (->> (project-include-labels project-id)
         (filter (fn [[aid labels]]
                   (not= 0 (->> labels (filter :resolve) count))))
         (apply concat)
         (apply hash-map))))

(defn project-user-inclusions [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :user-inclusions]
    (->>
     (-> (q/select-project-article-labels
          project-id true [:al.article-id :user-id :answer])
         (q/filter-overall-label)
         do-query)
     (group-by :user-id)
     (mapv (fn [[user-id entries]]
             (let [includes
                   (->> entries
                        (filter (comp true? :answer))
                        (mapv :article-id))
                   excludes
                   (->> entries
                        (filter (comp false? :answer))
                        (mapv :article-id))]
               [user-id {:includes includes
                         :excludes excludes}])))
     (apply concat)
     (apply hash-map))))

(defn project-article-status-counts [project-id]
  (with-project-cache
    project-id [:public-labels :status-counts]
    (let [articles (query-public-article-labels project-id)
          overall-id (project/project-overall-label-id project-id)]
      (when overall-id
        (let [status-vals
              (->> (vals articles)
                   (map
                    (fn [article]
                      (let [labels (get-in article [:labels overall-id])
                            group-status
                            (cond (is-single? labels)     :single
                                  (is-resolved? labels)   :resolved
                                  (is-conflict? labels)   :conflict
                                  :else                   :consistent)
                            inclusion-status
                            (case group-status
                              :conflict nil,
                              :resolved (->> labels (filter :resolve) (map :inclusion) first),
                              (->> labels (map :inclusion) first))]
                        [group-status inclusion-status]))))]
          (merge
           {:reviewed (count articles)}
           (->> (distinct status-vals)
                (map (fn [status]
                       [status (->> status-vals (filter (partial = status)) count)]))
                (apply concat)
                (apply hash-map))))))))

(defn project-article-statuses
  [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (let [status-vals
            (->> articles
                 (map
                  (fn [article]
                    (let [article-labels (second article)
                          labels (get-in article-labels [:labels overall-id])
                          group-status
                          (cond (is-single? labels)     :single
                                (is-resolved? labels)   :resolved
                                (is-conflict? labels)   :conflict
                                :else                   :consistent)
                          inclusion-status
                          (case group-status
                            :conflict nil,
                            :resolved (->> labels (filter :resolve) (map :inclusion) first),
                            (->> labels (map :inclusion) first))]
                      (hash-map :group-status group-status
                                :article-id (first article)
                                :answer (:answer (first labels)))))))]
        status-vals))))

(defn project-members-info [project-id]
  (with-project-cache
    project-id [:members-info]
    (let [users (->> (-> (q/select-project-members
                          project-id [:u.* [:m.permissions :project-permissions]])
                         do-query)
                     (group-by :user-id)
                     (map-values first))
          inclusions (project-user-inclusions project-id)
          in-progress
          (->> (-> (q/select-project-articles
                    project-id [:al.user-id :%count.%distinct.al.article-id])
                   (q/join-article-labels)
                   (q/filter-valid-article-label false)
                   (group :al.user-id)
                   do-query)
               (group-by :user-id)
               (map-values (comp :count first)))]
      (->> users
           (mapv (fn [[user-id user]]
                   [user-id
                    {:permissions (:project-permissions user)
                     :articles (get inclusions user-id)
                     :in-progress (if-let [count (get in-progress user-id)]
                                    count 0)}]))
           (apply concat)
           (apply hash-map)))))

(defn get-label-by-id
  "Get a label by its UUID label_id."
  [label-id]
  (-> (select :*)
      (from :label)
      (where [:= :label_id label-id])
      do-query first))

;; label validations

(defn used-label?
  "Has a label been set for an article?"
  [label-id]
  (if (= java.util.UUID
         (type label-id))
    (boolean (> (count (-> (select :article_id)
                           (from :article_label)
                           (where [:= :label_id label-id])
                           (do-query)))
                0))
    ;; label-id must be a UUID, otherwise above will throw an error
    ;; safe to assume that it hasn't been used if its label-id isn't
    ;; of the correct type
    false
    ))

(defn boolean-or-nil?
  "Is the value supplied boolean or nil?"
  [value]
  (or (boolean? value)
      (nil? value)))

(defn every-boolean-or-nil?
  "Is every value boolean or nil?"
  [value]
  (every? boolean-or-nil? value))

(defn editable-value-type?
  "If the label-id is a string (i.e. it doesn't yet exist on the server), the label hasn't been set for an article, or the value-type is the same as what exists on the server, return true. Otherwise, return false"
  [label-id value-type]
  (cond (string? label-id)
        true
        (not (used-label? label-id))
        true
        (used-label? label-id)
        (= (:value-type (get-label-by-id label-id))
           value-type)))

(defn only-deleteable-all-values-removed?
  "If the label-id is a string (i.e. it doesn't yet exist on the server), the label hasn't been set for an article, or all-values has not had entries deleted if the label does exist, return true. Otherwise, return false"
  [label-id all-values]
  (cond
    ;; label-id a string, the label has not been saved yet
    (string? label-id)
    true
    ;; the label hasn't been used yet
    (not (used-label? label-id))
    true
    ;; the label has been used
    (used-label? label-id)
    ;; ... so determine if a category has been deleted
    (clojure.set/superset? (set all-values)
                           (set (get-in (get-label-by-id label-id)
                                        [:definition :all-values])))))

(def boolean-definition-validations
  {:inclusion-values [[v/required
                       :message "Inclusion values must be included"]
                      [every-boolean-or-nil?
                       :message "Inclusion values must be boolean or nil"]]})

(def string-definition-validations
  {:multi?     [[v/required
                 :message "Allow multiple values responses must be set"]
                [boolean-or-nil?
                 :message "Allow multiple values must be true, false or nil"]]

   :examples   [[v/every string?
                 :message "Examples must be strings"]]

   :max-length [[v/required
                 :message "Max Length must be provided"]
                [v/integer
                 :message "Max length must be defined by an integer"]]

   :entity     [[v/string
                 :message "Entity must be defined by a string"]]})

(defn categorical-definition-validations
  [definition label-id]
  {:multi? [[v/required
             :message "A setting for multiple responses must be made"]
            [boolean-or-nil?
             :message "Allow multiple values must be true, false or nil"]]

   :all-values [[v/required
                 :message "A category must have defined options"]
                [sequential?
                 :message "Categories must be within a sequence"]
                [v/every string?
                 :message "All options must be strings"]
                [(partial only-deleteable-all-values-removed? label-id)
                 :message
                 (str "An option can not be removed from a category if
                 the label has already been set for an article. "
                      "The options for this label were originally "
                      (when-not (string? label-id)
                        (str/join "," (get-in (get-label-by-id label-id)
                                              [:definition :all-values]))))]]

   :inclusion-values [[sequential?
                       :message "Inclusion values must be within a sequence"]
                      [v/every #(contains? (set (:all-values definition)) %)
                       :message "All inclusion values must be within categories"]
                      [v/every string?
                       :message "All inclusion values must be strings"]]})

(defn label-validations
  "Given a label, return a validation map for it"
  [{:keys [value-type definition label-id]}]
  {:value-type [[v/required
                 :message "A label must have a type"]
                [v/string
                 :message "Label type must be a string"]
                [(partial contains? (set valid-label-value-types))
                 :message (str "A label must of type "
                               (str/join "," (rest valid-label-value-types)) " or " (first valid-label-value-types))]
                [(partial editable-value-type? label-id)
                 :message (str "You can not change the type of label if a user has already labeled an article with it. "
                               ;; get-label-by-id might encounter an error because a label with label-id doesn't exist on the server
                               (when-not (string? label-id)
                                 (str "The label was originally a "
                                      (:value-type (get-label-by-id label-id))
                                      " and has been set as "
                                      value-type)))
                 ]]

   :project-id [[v/required
                 :message "Project ID must not be blank"]
                [v/integer
                 :message "Project ID must be an integer"]]

   ;; these are going to be generated by the client so shouldn't
   ;; be blank, checking anyway
   :name [[v/required
           :message "Name must not be blank"]
          [v/string
           :message "Name must be a string"]]

   :question [[v/required
               :message "Question can not be blank"]
              [v/string
               :message "Question must be a string"]]

   :short-label [[v/required
                  :message "Label must have a name"]
                 [v/string
                  :message "Label must be a string"]]

   :inclusion-values [[boolean-or-nil?
                       :message "Inclusion must be true, false or nil"]]

   :required [[boolean-or-nil?
               :message "Required must be true, false or nil"]]

   ;; each value-type has a different definition
   :definition (condp = value-type
                 "boolean" boolean-definition-validations
                 "string" string-definition-validations
                 "categorical" (categorical-definition-validations definition label-id)
                 {})})
