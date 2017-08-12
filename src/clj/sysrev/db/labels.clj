(ns sysrev.db.labels
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.db.core :as db :refer
             [do-query do-query-map do-execute do-transaction
              sql-now to-sql-array to-jsonb sql-cast
              with-query-cache clear-query-cache
              with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :refer
             [project-labels project-overall-label-id project-settings]]
            [sysrev.db.articles :refer [query-article-by-id-full]]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.shared.labels :refer [cleanup-label-answer]]
            [sysrev.util :refer [crypto-rand crypto-rand-nth]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.math.numeric-tower :as math]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.coerce :as tc])
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
  (let [query
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
                      :enabled true}]))]
    (do-transaction nil (do-execute query))
    (db/clear-labels-cache project-id)
    (db/clear-project-cache project-id))
  true)

(defn add-label-entry-boolean
  "Creates an entry for a boolean label definition.

  `name` `question` `short-label` are strings describing the label.

  `inclusion-value` may be `true` or `false` to set that value as required
  for overall inclusion, or may be `nil` for no inclusion requirement.

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

  `max-length` is a required integer.
  `regex` is an optional vector of strings to require that answers must match
  one of the regex values.
  `entity` is an optional string to identify what the value represents.
  `examples` is an optional list of example strings to indicate to users
  the required format.
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
    (db/clear-labels-cache project-id)
    (db/clear-project-cache project-id)
    (-> (sqlh/update :label)
        (sset values-map)
        (where [:and
                [:= :label-id label-id]
                [:= :project-id project-id]])
        do-execute)))

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
          (-> (q/select-project-articles project-id [:a.article-id :al.user-id])
              (q/join-article-labels)
              (q/join-article-label-defs)
              (q/filter-valid-article-label nil)
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (let [user-ids (->> x (map :user-id) distinct vec)]
                        {:users user-ids})))))]
      (merge-with merge articles scores labels))))

(defn unlabeled-articles [project-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :unlabeled-articles predict-run-id]
    (->> (or articles (get-articles-with-label-users project-id predict-run-id))
         vals
         (filter #(= 0 (count (:users %))))
         (map #(dissoc % :users)))))

(defn single-labeled-articles [project-id self-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :single-labeled-articles self-id predict-run-id]
    (->> (or articles (get-articles-with-label-users project-id predict-run-id))
         vals
         (filter #(and (= 1 (count (:users %)))
                       (not (in? (:users %) self-id))))
         (map #(dissoc % :users)))))

(defn- pick-ideal-article
  "Used by the classify task functions to select an article from the candidates.

  Randomly picks from the top 5% of article entries sorted by `sort-keyfn`."
  [articles sort-keyfn & [predict-run-id]]
  (let [n-closest (max 200 (quot (count articles) 20))]
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

(defn user-confirmed-today-count [project-id user-id]
  (-> (q/select-project-article-labels project-id true [:al.article-id])
      (q/filter-label-user user-id)
      (merge-where [:=
                    (sql/call :date_trunc "day" :al.confirm-time)
                    (sql/call :date_trunc "day" :%now)])
      (->> do-query (map :article-id) distinct count)))

(defn get-user-label-task [project-id user-id]
  (let [{:keys [second-review-prob]
         :or {second-review-prob 0.5}} (project-settings project-id)
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
          :else nil)]
    (when (and article status)
      {:article-id (:article-id article)
       :today-count today-count})))

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
          (do-transaction
           nil
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
  (do-transaction
   nil
   (let [valid-values (->> label-values filter-valid-label-values)
         now (sql-now)
         project-id (:project-id (q/query-article-by-id article-id [:project-id]))
         current-entries
         (when change?
           (-> (q/select-article-by-id article-id [:al.*])
               (q/join-article-labels)
               (q/filter-label-user user-id)
               (->> (do-query)
                    (map #(dissoc %
                                  :article-label-id
                                  :article-label-local-id)))))
         overall-label-id (project-overall-label-id project-id)
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
                          :imported imported?
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
     (db/clear-project-label-values-cache project-id confirm? user-id)
     (db/clear-project-member-cache project-id user-id)
     (db/clear-project-article-cache project-id article-id)
     true)))

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

(defn query-public-article-labels
  [project-id & {:keys [exclude-hours]}]
  (let [cutoff-epoch
        (when exclude-hours
          (tc/to-epoch (t/minus (tc/from-sql-date (sql-now))
                                (t/hours exclude-hours))))

        include-article?
        (fn [entries]
          (cond (nil? cutoff-epoch) true
                (empty? entries)    false
                :else
                (let [edit-epoch
                      (->> entries (map :confirm-epoch) (apply max))]
                  (< edit-epoch cutoff-epoch))))

        [all-articles all-labels]
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
         (-> (select :a.article-id :al.label-id :al.answer :al.inclusion
                     :al.resolve :al.confirm-time :wu.user-id)
             (from [:article :a])
             (join [:article-label :al] [:= :a.article_id :al.article_id]
                   [:web-user :wu] [:= :al.user-id :wu.user-id])
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
                  (map-values (fn [entries]
                                (let [user-ids (->> entries (map :user-id) distinct)]
                                  (if (> (count user-ids) 1)
                                    entries []))))
                  (map-values (fn [entries]
                                (if (include-article? entries)
                                  entries [])))
                  (filter (fn [[article-id entries]]
                            (not-empty entries)))
                  (apply concat)
                  (apply hash-map)
                  (map-values (fn [entries]
                                (map #(dissoc % :article-id) entries)))
                  (map-values (fn [entries]
                                {:labels entries
                                 :updated-time (->> entries (map :confirm-epoch)
                                                    (apply max))})))))]
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
         (apply hash-map))))

(defn query-member-articles [project-id user-id]
  (let [articles
        (-> (select :a.article-id :a.primary-title :al.answer :al.inclusion
                    :al.resolve :al.confirm-time :al.updated-time :al.label-id
                    :wu.user-id)
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
        notes
        (-> (q/select-project-articles
             project-id [:a.article-id :an.content :pn.name])
            (q/with-article-note nil user-id)
            (->> do-query
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
