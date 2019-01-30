(ns sysrev.label.core
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

(defn article-consensus-status [project-id article-id]
  (let [overall-id (project/project-overall-label-id project-id)
        consensus-ids (project/project-consensus-label-ids project-id)
        alabels (-> (query-public-article-labels project-id)
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
