(ns sysrev.label.core
  (:require [bouncer.validators :as v]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction
              sql-now to-sql-array to-jsonb sql-cast
              with-query-cache with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.article.core :as article]
            [sysrev.util :as util :refer [crypto-rand crypto-rand-nth]]
            [sysrev.shared.util :as su :refer [map-values in?]]
            [sysrev.shared.labels :refer [cleanup-label-answer]])
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

(defn get-label-by-id
  "Get a label by its UUID label_id."
  [label-id]
  (-> (select :*)
      (from :label)
      (where [:= :label-id label-id])
      do-query first))

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

;; TODO: move into article entity
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

(defn query-public-article-labels [project-id]
  (with-project-cache
    project-id [:public-labels :values]
    (let [[all-articles all-labels all-resolve]
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
                                                      (apply max 0))}))))
           (-> (select :ar.*)
               (from [:article-resolve :ar])
               (join [:article :a] [:= :a.article-id :ar.article-id])
               (where [:and
                       [:= :a.project-id project-id]
                       [:= :a.enabled true]])
               (->> do-query
                    (group-by :article-id)
                    (map-values
                     (fn [entries]
                       (->> entries
                            (sort-by #(-> % :resolve-time tc/to-epoch) >)
                            first)))
                    (map-values
                     (fn [resolve]
                       (when resolve
                         (update resolve :label-ids #(mapv su/to-uuid %))))))))]
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
                            (map #(dissoc % :label-id :confirm-epoch) entries))))
                    resolve (get all-resolve article-id)]
                [article-id {:title (:primary-title article)
                             :updated-time updated-time
                             :labels labels
                             :resolve resolve}])))
           (apply concat)
           (apply hash-map)))))

(defn query-progress-over-time [project-id n-days]
  (with-project-cache
    project-id [:public-labels :progress n-days]
    (let [overall-id (project/project-overall-label-id project-id)
          #_ completed #_ nil
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
                 #_ :completed #_ (->> completed
                                       (filter #(< (:updated-time %) day-epoch))
                                       count)
                 :labeled (->> labeled
                               (filter #(< (:updated-time %) day-epoch))
                               (map :users)
                               (apply +))})))))))

(defn- article-current-resolve-entry
  "Returns most recently created article_resolve entry for article. By
  default, the value is taken from query-public-article-labels
  (cached function); use keyword argument direct? to query from
  database directly."
  [project-id article-id & {:keys [direct?]}]
  (if direct?
    (-> (select :*)
        (from :article-resolve)
        (where [:= :article-id article-id])
        (order-by [:resolve-time :desc])
        (limit 1)
        (some-> do-query
                first
                (update :label-ids #(mapv su/to-uuid %))))
    (-> (query-public-article-labels project-id)
        (get article-id)
        :resolve)))

(defn article-conflict-label-ids
  "Returns list of consensus labels in project for which article has
  conflicting answers."
  [project-id article-id]
  (let [alabels (-> (query-public-article-labels project-id)
                    (get article-id))
        user-ids (->> alabels :labels vals
                      (apply concat) (map :user-id) distinct)
        user-label-answer (fn [user-id label-id]
                            (->> (get-in alabels [:labels label-id])
                                 (filter #(= (:user-id %) user-id))
                                 (map :answer)
                                 first))
        ;; Get answers in this way to include nil values where
        ;; a user has not answered the particular label.
        ;;
        ;; Non-answers may generate conflicts with answers.
        label-answers (fn [label-id]
                        (->> user-ids (map #(user-label-answer % label-id))))]
    (->> (project/project-consensus-label-ids project-id)
         (filter #(-> (label-answers %) distinct count (> 1))))))

(defn article-resolved-status
  "If article consensus status is resolved, returns a map of the
  corresponding article_resolve entry; otherwise returns nil.

  Consensus status is resolved if an article_resolve entry exists,
  unless the label_ids field of the entry does not contain all active
  project consensus labels."
  [project-id article-id]
  (when-let [resolve (article-current-resolve-entry project-id article-id)]
    (let [conflict-ids (article-conflict-label-ids project-id article-id)]
      (if (empty? (set/difference
                   (set conflict-ids)
                   (set (:label-ids resolve))))
        (dissoc resolve :article-id)
        nil))))

(defn article-consensus-status
  "Returns keyword representing consensus status of confirmed answers
  for article, or nil if article has no confirmed answers."
  [project-id article-id]
  (let [overall-id (project/project-overall-label-id project-id)
        overall-labels (-> (query-public-article-labels project-id)
                           (get article-id)
                           (get-in [:labels overall-id]))
        conflict-ids (article-conflict-label-ids project-id article-id)
        resolve (article-resolved-status project-id article-id)]
    (cond (empty? overall-labels)       nil
          (= 1 (count overall-labels))  :single
          resolve                       :resolved
          (not-empty conflict-ids)      :conflict
          :else                         :consistent)))

(defn article-resolved-labels
  "If article consensus status is resolved, returns map of {label-id
  answer} for answers provided by resolving user; otherwise returns
  nil."
  [project-id article-id]
  (when-let [resolve (article-resolved-status project-id article-id)]
    (when (user-article-confirmed? (:user-id resolve) article-id)
      (->> (-> (article-user-labels-map project-id article-id)
               (get (:user-id resolve)))
           (map-values :answer)))))

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
                      :conflict nil
                      :resolved (-> (article-resolved-labels project-id article-id)
                                    (get overall-id))
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

(defn project-article-status-entries [project-id]
  (with-project-cache
    project-id [:public-labels :status-entries]
    (let [articles (query-public-article-labels project-id)
          overall-id (project/project-overall-label-id project-id)]
      (when overall-id
        (->>
         articles
         (mapv
          (fn [[article-id entry]]
            (let [labels (get-in entry [:labels overall-id])
                  group-status
                  (article-consensus-status project-id article-id)
                  inclusion-status
                  (case group-status
                    :conflict nil
                    :resolved (-> (article-resolved-labels project-id article-id)
                                  (get overall-id))
                    (->> labels (map :inclusion) first))]
              {article-id [group-status inclusion-status]})))
         (apply merge))))))

(defn project-article-status-counts [project-id]
  (with-project-cache
    project-id [:public-labels :status-counts]
    (let [entries (project-article-status-entries project-id)
          articles (query-public-article-labels project-id)]
      (merge
       {:reviewed (count articles)}
       (->> (distinct (vals entries))
            (map (fn [status]
                   [status (->> (vals entries) (filter (partial = status)) count)]))
            (apply concat)
            (apply hash-map))))))

(defn project-article-statuses
  [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (->> articles
           (mapv
            (fn [[article-id article-labels]]
              (let [labels (get-in article-labels [:labels overall-id])
                    group-status
                    (article-consensus-status project-id article-id)
                    inclusion-status
                    (case group-status
                      :conflict nil
                      :resolved (-> (article-resolved-labels project-id article-id)
                                    (get overall-id))
                      (->> labels (map :inclusion) first))]
                (hash-map :group-status group-status
                          :article-id article-id
                          :answer (:answer (first labels))))))))))

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
