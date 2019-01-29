(ns sysrev.db.labels
  (:require [bouncer.validators :as v]
            [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction
              sql-now to-sql-array to-jsonb sql-cast
              with-query-cache with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.article.core :as article]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.shared.labels :refer [cleanup-label-answer]]
            [sysrev.shared.article-list :refer
             [is-resolved? is-conflict? is-single? is-consistent?]]
            [sysrev.util :refer [crypto-rand crypto-rand-nth]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clojure.string :as str])
  (:import java.util.UUID))

(def valid-label-categories
  ["inclusion criteria" "extra"])
(def valid-label-value-types
  ["boolean" "categorical" "string"])

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
  [project-id {:keys [name question short-label category
                      required consensus value-type definition]}]
  (assert (in? valid-label-categories category))
  (assert (in? valid-label-value-types value-type))
  (try
    (-> (insert-into :label)
        (values [(cond-> {:project-id project-id
                          :project-ordering (q/next-label-project-ordering project-id)
                          :value-type value-type
                          :name name
                          :question question
                          :short-label short-label
                          :required required
                          :category category
                          :definition (to-jsonb definition)
                          :enabled true}
                   (boolean? consensus)
                   (merge {:consensus consensus})
                   (= name "overall include")
                   (merge {:consensus true}))])
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
           consensus custom-category]
    :as entry-values}]
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required :consensus]
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
           required consensus multi? custom-category]
    :as entry-values}]
  (assert (sequential? all-values))
  (assert (sequential? inclusion-values))
  (add-label-entry
   project-id
   (merge
    (->> [:name :question :short-label :required :consensus]
         (select-keys entry-values))
    {:value-type "categorical"
     :category (or custom-category
                   (if (empty? inclusion-values)
                     "extra" "inclusion criteria"))
     :definition {:all-values all-values
                  :inclusion-values inclusion-values
                  :multi? (boolean multi?)}})))

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
   {:keys [name question short-label required consensus custom-category
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
    (->> [:name :question :short-label :required :consensus]
         (select-keys entry-values))
    {:value-type "string"
     :category (or custom-category "extra")
     :definition (cond->
                     {:multi? multi?
                      :max-length max-length}
                   regex (assoc :regex regex)
                   entity (assoc :entity entity)
                   examples (assoc :examples examples))})))

(defn add-label-overall-include [project-id]
  (add-label-entry-boolean
   project-id {:name "overall include"
               :question "Include this article?"
               :short-label "Include"
               :inclusion-value true
               :required true
               :consensus true}))

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

(defn article-user-labels-map [project-id article-id]
  (-> (q/select-article-by-id article-id [:al.*])
      (q/join-article-labels)
      (->> do-query
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
                                       (tc/to-epoch updated-time)))})))))))))

(defn user-article-confirmed? [user-id article-id]
  (assert (and (integer? user-id) (integer? article-id)))
  (-> (q/select-article-by-id article-id [:%count.*])
      (q/join-article-labels)
      (q/filter-label-user user-id)
      (merge-where [:!= :al.confirm-time nil])
      do-query first :count pos?))

(defn label-answer-valid? [label-id answer]
  (let [label (get (all-labels-cached) label-id)]
    (case (:value-type label)
      "boolean"
      (when (in? [true false nil] answer)
        {label-id answer})
      "categorical"
      (cond (nil? answer)
            {label-id answer}
            (sequential? answer)
            (let [allowed (-> label :definition :all-values)]
              (when (every? (in? allowed) answer)
                {label-id answer}))
            :else nil)
      ;; TODO check that answer value matches label regex
      "string" (when (coll? answer)
                 (let [filtered (->> answer
                                     (filter string?)
                                     (filterv not-empty))]
                   (cond (empty? filtered)
                         {label-id nil}
                         (every? string? filtered)
                         {label-id filtered}
                         :else nil)))
      {label-id answer})))

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
       (mapv
        (fn [[label-id answer]]
          (label-answer-valid? label-id answer)))
       (remove nil?)
       (apply merge)))

;; TODO: check that all required labels are answered
(defn set-user-article-labels
  "Set article-id for user-id with a map of label-values. imported? is vestigal, confirm? set to true will set the confirm_time to now, change? set to true will set the updated_time to now, resolve will be set to resolve? The format of label-values is
  {<label-id-1> <value-1>, <label-id-2> <value-2>, ... , <label-id-n> <value-n>}.

  It should be noted that a label's confirm value must be set to true in order to be recognized as a labeled article in the rest of the app. This includes article summary graphs, compensations, etc. e.g. If confirm? is not to set true, then a user would not be compensated for that article."
  [user-id article-id label-values &
   {:keys [imported? confirm? change? resolve?]}]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (with-transaction
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
      true)))

;; TODO: can inclusion-values be changed with existing answers?
;;       if yes, need to run this.
;;       if no, can delete this.
(defn update-label-answer-inclusion [label-id]
  (with-transaction
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
           doall))))

(defn set-label-enabled [label-id enabled?]
  (let [label-id (q/to-label-id label-id)]
    (-> (sqlh/update :label)
        (sset {:enabled enabled?})
        (where [:= :label-id label-id])
        do-execute)))
;;;
(s/fdef set-label-enabled
  :args (s/cat :label-id ::sc/label-id, :enabled? boolean?))

(defn set-label-required [label-id required?]
  (let [label-id (q/to-label-id label-id)]
    (-> (sqlh/update :label)
        (sset {:required required?})
        (where [:= :label-id label-id])
        do-execute)))
;;;
(s/fdef set-label-required
  :args (s/cat :label-id ::sc/label-id, :required? boolean?))

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
               (join [:article-label :al] [:= :a.article-id :al.article-id]
                     [:label :l] [:= :al.label-id :l.label-id])
               (where [:and
                       [:= :a.project-id project-id]
                       [:= :a.enabled true]
                       [:= :l.enabled true]
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
          completed (->> (vals (query-public-article-labels project-id))
                         (filter
                          (fn [{:keys [labels]}]
                            (let [overall (get labels overall-id)]
                              (and overall
                                   (or (is-consistent? overall)
                                       (is-resolved? overall)))))))
          labeled (->> (vals (query-public-article-labels project-id))
                       (map
                        (fn [{:keys [labels updated-time]}]
                          (let [overall (get labels overall-id)
                                users (->> (vals labels)
                                           (apply concat)
                                           (map :user-id)
                                           distinct
                                           count)]
                            {:updated-time updated-time
                             :users users}))))
          now (tc/to-epoch (t/now))
          day-seconds (* 60 60 24)
          tformat (tf/formatters :year-month-day)]
      (->> (range 0 n-days)
           (mapv
            (fn [day-idx]
              (let [day-epoch (- now (* day-idx day-seconds))]
                {:day (tf/unparse tformat (tc/from-long (* 1000 day-epoch)))
                 :completed (->> completed
                                 (filter #(< (:updated-time %) day-epoch))
                                 count)
                 :labeled (->> labeled
                               (filter #(< (:updated-time %) day-epoch))
                               (map :users)
                               (apply +))})))))))

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

(defn article-consensus-status [project-id article-id]
  (let [overall-id (project/project-overall-label-id project-id)
        labels (project/project-labels project-id)
        label-ids (keys labels)
        consensus-ids
        (->> label-ids (filter #(-> (get labels %) :consensus true?)))
        alabels
        (-> (query-public-article-labels project-id)
            (get article-id))]
    (cond (or (empty? alabels)
              (empty? (get-in alabels [:labels overall-id])))
          nil

          (is-single? (get-in alabels [:labels overall-id]))
          :single

          ;; TODO: change resolve handling
          (is-resolved? (get-in alabels [:labels overall-id]))
          :resolved

          (some (fn [label-id]
                  (is-conflict? (get-in alabels [:labels label-id])))
                consensus-ids)
          :conflict

          :else :consistent)))

(defn project-included-articles [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (->> (vec articles)
           (filter
            (fn [[article-id article]]
              (let [labels (get-in article [:labels overall-id])
                    group-status (article-consensus-status project-id article-id)
                    inclusion-status
                    (case group-status
                      :conflict nil,
                      ;; TODO: update resolve handling
                      :resolved (->> labels (filter :resolve) (map :inclusion) first),
                      (->> labels (map :inclusion) first))]
                (and (in? [:consistent :resolved] group-status)
                     (true? inclusion-status)))))
           (apply concat)
           (apply hash-map)))))

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
              (->> articles
                   (map
                    (fn [[article-id entry]]
                      (let [labels (get-in entry [:labels overall-id])
                            group-status
                            (article-consensus-status project-id article-id)
                            inclusion-status
                            (case group-status
                              :conflict nil
                              :resolved (->> labels
                                             (filter :resolve) (map :inclusion)
                                             first)
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
                  (fn [[article-id article-labels]]
                    (let [labels (get-in article-labels [:labels overall-id])
                          group-status
                          (article-consensus-status project-id article-id)
                          inclusion-status
                          (case group-status
                            :conflict nil,
                            :resolved (->> labels (filter :resolve) (map :inclusion) first),
                            (->> labels (map :inclusion) first))]
                      (hash-map :group-status group-status
                                :article-id article-id
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
      (where [:= :label-id label-id])
      do-query first))

;; label validations

(defn used-label?
  "Has a label been set for an article?"
  [label-id]
  (cond
    ;; string value implies label is not yet created (?)
    (string? label-id) false

    (uuid? label-id)
    (boolean (> (count (-> (select :article-id)
                           (from :article-label)
                           (where [:= :label-id label-id])
                           (do-query)))
                0))

    :else
    (throw (Exception. "used-label? - invalid label-id value"))))

(defn boolean-or-nil?
  "Is the value supplied boolean or nil?"
  [value]
  (or (boolean? value)
      (nil? value)))

(defn every-boolean-or-nil?
  "Is every value boolean or nil?"
  [value]
  (and (seqable? value)
       (every? boolean-or-nil? value)))

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
  {:inclusion-values
   [[every-boolean-or-nil?
     :message "[Error] Invalid value for \"Inclusion Values\""]]})

(def string-definition-validations
  {:multi?     [[boolean-or-nil?
                 :message "[Error] Invalid value for \"Multiple Values\""]]

   :examples   [[v/every string?
                 :message "[Error] Invalid value for \"Examples\""]]

   :max-length [[v/required
                 :message "Max length must be provided"]
                [v/integer
                 :message "Max length must be an integer"]]

   :entity     [[v/string
                 :message "[Error] Invalid value for \"Entity\""]]})

(defn categorical-definition-validations
  [definition label-id]
  {:multi? [[boolean-or-nil?
             :message "Allow multiple values must be true, false or nil"]]

   :all-values [[v/required
                 :message "Category options must be provided"]
                [sequential?
                 :message "[Error] Categories value is non-sequential"]
                [v/every string?
                 :message "[Error] Invalid value for \"Categories\""]
                [(partial only-deleteable-all-values-removed? label-id)
                 :message
                 (str "An option can not be removed from a category if
                 the label has already been set for an article. "
                      "The options for this label were originally "
                      (when-not (string? label-id)
                        (str/join "," (get-in (get-label-by-id label-id)
                                              [:definition :all-values]))))]]

   :inclusion-values [[sequential?
                       :message "[Error] Inclusion Values is non-sequential"]
                      [v/every string?
                       :message "[Error] Invalid value for \"Inclusion Values\""]
                      [v/every #(contains? (set (:all-values definition)) %)
                       :message "Inclusion values must each be present in list of categories"]]})

(defn label-validations
  "Given a label, return a validation map for it"
  [{:keys [value-type required definition label-id]}]
  {:value-type [[v/required
                 :message "[Error] Label type is not set"]
                [v/string
                 :message "[Error] Invalid value for label type (non-string)"]
                [(partial contains? (set valid-label-value-types))
                 :message "[Error] Invalid value for label type (option not valid)"]
                [(partial editable-value-type? label-id)
                 :message
                 (str "You can not change the type of label if a user has already labeled an article with it. "
                      ;; get-label-by-id might encounter an error because a label with label-id doesn't exist on the server
                      (when-not (string? label-id)
                        (str "The label was originally a "
                             (:value-type (get-label-by-id label-id))
                             " and has been set as "
                             value-type)))]]

   :project-id [[v/required
                 :message "[Error] Project ID not set"]
                [v/integer
                 :message "[Error] Project ID is not integer"]]

   ;; these are going to be generated by the client so shouldn't
   ;; be blank, checking anyway
   :name [[v/required
           :message "Label name must be provided"]
          [v/string
           :message "[Error] Invalid value for \"Label Name\""]]

   :question [[v/required
               :message "Question text must be provided"]
              [v/string
               :message "[Error] Invalid value for \"Question\""]]

   :short-label [[v/required
                  :message "Display name must be provided"]
                 [v/string
                  :message "[Error] Invalid value for \"Display Name\""]]

   :required [[boolean-or-nil?
               :message "[Error] Invalid value for \"Required\""]]

   :consensus [[#(not (and (true? %) (false? required)))
                :message "Answer must be required when requiring consensus"]]

   ;; each value-type has a different definition
   :definition (condp = value-type
                 "boolean" boolean-definition-validations
                 "string" string-definition-validations
                 "categorical" (categorical-definition-validations definition label-id)
                 {})})

;; TODO: do this later maybe
;;
;; note: this was related to identifying categorical label values
;;       using a uuid rather than directly by their text value
#_
(defn migrate-label-uuid-values [label-id]
  (with-transaction
    (let [{:keys [value-type definition]}
          (-> (select :value-type :definition)
              (from :label)
              (where [:= :label-id label-id])
              do-query first)
          {:keys [all-values inclusion-values]} definition]
      (when (and (= value-type "categorical") (vector? all-values))
        (let [new-values (->> all-values
                              (map (fn [v] {(UUID/randomUUID) {:name v}}))
                              (apply merge))
              to-uuid (fn [v]
                        (->> (keys new-values)
                             (filter #(= v (get-in new-values [% :name])))
                             first))
              new-inclusion (->> inclusion-values (map to-uuid) (remove nil?) vec)
              al-entries (-> (select :article-label-id :answer)
                             (from :article-label)
                             (where [:= :label-id label-id])
                             do-query)]
          (doseq [{:keys [article-label-id answer]} al-entries]
            (-> (sqlh/update :article-label)
                (sset {:answer (to-jsonb (some->> answer (mapv to-uuid)))})
                (where [:= :article-label-id article-label-id])
                do-execute))
          (-> (sqlh/update :label)
              (sset {:definition
                     (to-jsonb
                      (assoc definition
                             :all-values new-values
                             :inclusion-values new-inclusion))})
              (where [:= :label-id label-id])
              do-execute))))))
