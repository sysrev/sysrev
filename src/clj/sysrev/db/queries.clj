(ns sysrev.db.queries
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute sql-now to-sql-array to-jsonb
              sql-field with-project-cache clear-project-cache
              clear-query-cache sql-array-contains]]
            [sysrev.shared.util :as u :refer [in?]])
  (:import java.util.UUID))

;;;
;;; id conversions
;;;

(defn to-article-id
  "Returns integer article id of argument."
  [article-or-id]
  (let [in (s/conform ::sa/article-or-id article-or-id)]
    (if (= in ::s/invalid)
      nil
      (let [[t v] in]
        (cond
          (= t :map) (:article-id article-or-id)
          (= t :id)
          (let [id (s/unform ::sc/article-id v)]
            (if (integer? id)
              id
              (-> (select :article-id)
                  (from :article)
                  (where [:= :article-uuid id])
                  do-query first :article-id)))
          :else nil)))))
;;
(s/fdef to-article-id
        :args (s/cat :article-or-id ::sa/article-or-id)
        :ret (s/nilable ::sc/sql-serial-id))

(defn to-user-id
  "Returns integer user id of argument."
  [user-id]
  (let [in (s/conform ::sc/user-id user-id)]
    (if (= in ::s/invalid)
      nil
      (let [[t v] in]
        (cond
          (= t :serial) user-id
          (= t :uuid) (-> (select :user-id)
                          (from :web-user)
                          (where [:= :user-uuid user-id])
                          do-query first :user-id)
          :else nil)))))
;;
(s/fdef to-user-id
        :args (s/cat :user-id ::sc/user-id)
        :ret (s/nilable ::sc/sql-serial-id))

(defn to-project-id
  "Returns integer project id of argument."
  [project-id]
  (let [in (s/conform ::sc/project-id project-id)]
    (if (= in ::s/invalid)
      nil
      (let [[t v] in]
        (cond
          (= t :serial) project-id
          (= t :uuid) (-> (select :project-id)
                          (from :project)
                          (where [:= :project-uuid project-id])
                          do-query first :project-id)
          :else nil)))))
;;
(s/fdef to-project-id
        :args (s/cat :project-id ::sc/project-id)
        :ret (s/nilable ::sc/sql-serial-id))

(defn to-label-id
  "Returns label uuid of argument."
  [label-id]
  (let [in (s/conform ::sc/label-id label-id)]
    (if (= in ::s/invalid)
      nil
      (let [[t v] in]
        (cond
          (= t :uuid) label-id
          (= t :serial) (-> (select :label-id)
                            (from :label)
                            (where [:= :label-id-local label-id])
                            do-query first :label-id)
          :else nil)))))
;;
(s/fdef to-label-id
        :args (s/cat :label-id ::sc/label-id)
        :ret (s/nilable ::sc/uuid))

;;;
;;; articles
;;;

(defn select-project-articles
  "Constructs a honeysql query to select the articles in project-id.

   Defaults to excluding any disabled articles.

   Set option include-disabled? as true to include all disabled articles.

   Set option include-disabled-source? as true to exclude only articles
   which are disabled by an article-flag entry.

   Only one of [include-disabled? include-disabled-source?] should be set."
  [project-id fields & [{:keys [include-disabled? tname include-disabled-source?]
                         :or {include-disabled? false
                              tname :a
                              include-disabled-source? false}
                         :as opts}]]
  (cond->
      (-> (apply select fields)
          (from [:article tname]))
    project-id
    (merge-where [:= (sql-field tname :project-id) project-id])

    (and (not include-disabled?)
         (not include-disabled-source?))
    (merge-where [:= (sql-field tname :enabled) true])

    include-disabled-source?
    (merge-where
     [:not
      [:exists
       (-> (select :*)
           (from [:article-flag :af-test])
           (where [:and
                   [:= :af-test.disable true]
                   [:=
                    :af-test.article-id
                    (sql-field tname :article-id)]]))]])))

(defn select-article-where
  [project-id where-clause fields & [{:keys [include-disabled? tname]
                                      :as opts}]]
  (cond->
      (select-project-articles project-id fields opts)
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)))

(defn set-article-enabled-where
  [enabled? where-clause & [project-id]]
  (assert (in? [true false] enabled?))
  (-> (sqlh/update [:article :a])
      (sset {:enabled enabled?})
      (where [:and
              where-clause
              (if project-id
                [:= :a.project-id project-id]
                true)])
      do-execute))

(defn select-article-by-id
  [article-id fields & [{:keys [include-disabled? tname project-id]
                         :or {include-disabled? true
                              tname :a
                              project-id nil}
                         :as opts}]]
  (select-article-where
   project-id
   (if (or (string? article-id) (uuid? article-id))
     [:= (sql-field tname :article-uuid) article-id]
     [:= (sql-field tname :article-id) article-id])
   fields
   {:include-disabled? include-disabled?
    :tname tname}))

(defn filter-article-by-disable-flag
  [m disabled? & [{:keys [tname] :or {tname :a}}
                  :as opts]]
  (let [exists
        [:exists
         (-> (select :*)
             (from [:article-flag :af-filter])
             (where [:and
                     [:= :af-filter.disable true]
                     [:=
                      :af-filter.article-id
                      (sql-field tname :article-id)]]))]]
    (cond-> m
      disabled? (merge-where exists)
      (not disabled?) (merge-where [:not exists]))))

(defn filter-article-by-location [m source external-id]
  (-> m
      (merge-where
       [:exists
        (-> (select :*)
            (from [:article-location :al-f])
            (where [:and
                    [:= :a.article-id :al-f.article-id]
                    [:= :al-f.source source]
                    [:= :al-f.external-id external-id]]))])))

(defn select-article-by-external-id
  [source external-id
   fields & [{:keys [include-disabled? project-id]
              :or {include-disabled? true
                   project-id nil}
              :as opts}]]
  (-> (select-article-where
       project-id true fields
       {:include-disabled? include-disabled?})
      (filter-article-by-location source external-id)))

(defn query-article-by-id [article-id fields & [opts]]
  (-> (select-article-by-id article-id fields opts)
      do-query first))

(defn query-article-locations-by-id [article-id fields]
  (if (or (string? article-id) (uuid? article-id))
    (-> (apply select fields)
        (from [:article-location :al])
        (join [:article :a]
              [:= :a.article-id :al.article-id])
        (where [:= :a.article-uuid article-id])
        do-query)
    (-> (apply select fields)
        (from [:article-location :al])
        (where [:= :al.article-id article-id])
        do-query)))

(defn join-article-locations [m & [{:keys [tname-a tname-aloc]
                                    :or {tname-a :a
                                         tname-aloc :aloc}}]]
  (-> m (merge-join [:article-location tname-aloc]
                    [:=
                     (sql-field tname-aloc :article-id)
                     (sql-field tname-a :article-id)])))

;;;
;;; labels
;;;

(defn select-label-by-id
  [label-id fields & [{:keys [include-disabled?]
                       :or {include-disabled? true}
                       :as opts}]]
  (assert (or (in? [UUID String] (type label-id))
              (integer? label-id)))
  (cond->
      (-> (apply select fields)
          (from [:label :l]))
    (integer? label-id)
    (merge-where [:= :label-id-local label-id])
    (not (integer? label-id))
    (merge-where [:= :label-id label-id])
    (not include-disabled?)
    (merge-where [:= :enabled true])))

(defn delete-label-by-id [label-id]
  (assert (or (in? [UUID String] (type label-id))
              (integer? label-id)))
  (-> (delete-from :label)
      (where (if (integer? label-id)
               [:= :label-id-local label-id]
               [:= :label-id label-id]))))

(defn select-label-where
  [project-id where-clause fields & [{:keys [include-disabled?]
                                      :or {include-disabled? false}
                                      :as opts}]]
  (cond->
      (-> (apply select fields)
          (from [:label :l]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)
    project-id (merge-where [:= :project-id project-id])
    (not include-disabled?) (merge-where [:= :enabled true])))

(defn next-label-project-ordering [project-id]
  (let [max
        (-> (select [:%max.project-ordering :max])
            (from :label)
            (where [:= :project-id project-id])
            do-query first :max)]
    (if max (inc max) 0)))

(defn query-label-by-name [project-id label-name fields & [opts]]
  (-> (select-label-where project-id [:= :name label-name] fields opts)
      do-query first))

(defn query-label-where [project-id where-clause fields]
  (-> (select-label-where project-id where-clause [:*])
      do-query first))

(defn label-id-from-name [project-id label-name]
  (:label-id (query-label-where project-id [:= :name label-name] [:label-id])))

(defn query-project-labels [project-id fields]
  (-> (select-label-where project-id true fields)
      do-query))

(defn label-confirmed-test [confirmed?]
  (case confirmed?
    true [:!= :confirm-time nil]
    false [:= :confirm-time nil]
    true))

(defn join-article-labels [m & [{:keys [tname-a tname-al]
                                 :or {tname-a :a
                                      tname-al :al}}]]
  (-> m (merge-join [:article-label tname-al]
                    [:=
                     (sql-field tname-al :article-id)
                     (sql-field tname-a :article-id)])))

(defn join-article-label-defs [m]
  (-> m (merge-join [:label :l]
                    [:= :l.label-id :al.label-id])))

(defn join-article-flags [m]
  (-> m (merge-join [:article-flag :aflag]
                    [:= :aflag.article-id :a.article-id])))

(defn filter-valid-article-label [m confirmed?]
  (-> m (merge-where [:and
                      (label-confirmed-test confirmed?)
                      [:!= :al.answer nil]
                      [:!= :al.answer (to-jsonb nil)]
                      [:!= :al.answer (to-jsonb [])]
                      [:!= :al.answer (to-jsonb {})]])))

(defn filter-label-user [m user-id]
  (-> m (merge-where [:= :al.user-id user-id])))

(defn filter-label-name [m label-name]
  (-> m (merge-where [:= :l.name label-name])))

(defn filter-overall-label [m]
  (-> m (filter-label-name "overall include")))

(defn select-project-article-labels [project-id confirmed? fields]
  (-> (select-project-articles project-id fields)
      (join-article-labels)
      (join-article-label-defs)
      (filter-valid-article-label confirmed?)))

(defn select-user-article-labels
  [user-id article-id confirmed? fields]
  (-> (apply select fields)
      (from [:article-label :al])
      (where [:and
              [:= :al.user-id user-id]
              [:= :al.article-id article-id]])
      (filter-valid-article-label confirmed?)))

;;;
;;; projects
;;;

(defn select-project-where [where-clause fields]
  (cond->
      (-> (apply select fields)
          (from [:project :p]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)))

(defn query-project-by-id [project-id fields]
  (-> (select-project-where [:= :p.project-id project-id] fields)
      do-query first))

(defn query-project-by-uuid [project-uuid fields & [conn]]
  (-> (select-project-where [:= :p.project-uuid project-uuid] fields)
      (do-query conn)))

;;;
;;; keywords
;;;
(defn select-project-keywords [project-id fields]
  (-> (apply select fields)
      (from [:project-keyword :pkw])
      (where [:= :pkw.project-id project-id])))

;;;
;;; users
;;;

(defn select-project-members [project-id fields]
  (-> (apply select fields)
      (from [:project-member :m])
      (join [:web-user :u]
            [:= :u.user-id :m.user-id])
      (where [:= :m.project-id project-id])))

(defn join-users [m user-id]
  (-> m
      (merge-join [:web-user :u]
                  [:= :u.user-id user-id])))

(defn join-user-member-entries [m project-id]
  (-> m
      (merge-join [:project-member :m]
                  [:= :m.user-id :u.user-id])
      (merge-where [:= :m.project-id project-id])))

(defn filter-user-permission [m permission & [not?]]
  (let [test (sql-array-contains :u.permissions permission)
        test (if not? [:not test] test)]
    (merge-where m test)))
;;
(s/fdef filter-user-permission
        :args (s/cat :m ::sc/honeysql
                     :permission string?
                     :not? (s/? boolean?))
        :ret ::sc/honeysql)

(defn filter-admin-user [m admin?]
  (cond-> m
    (true? admin?) (filter-user-permission "admin")
    (false? admin?) (filter-user-permission "admin" true)
    (nil? admin?) (identity)))
;;
(s/fdef filter-admin-user
        :args (s/cat :m ::sc/honeysql
                     :admin? (s/nilable boolean?))
        :ret ::sc/honeysql)

;;;
;;; predict values
;;;

(defn select-predict-run-where [where-clause fields]
  (cond-> (-> (apply select fields)
              (from [:predict-run :pr]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)))

(defn query-predict-run-by-id [predict-run-id fields]
  (-> (select-predict-run-where
       [:= :predict-run-id predict-run-id] fields)
      do-query first))

(defn join-article-predict-values [m & [predict-run-id]]
  (cond-> m
    true (merge-left-join [:label-predicts :lp]
                          [:= :lp.article-id :a.article-id])
    predict-run-id (merge-where [:or
                                 [:= :lp.predict-run-id predict-run-id]
                                 [:= :lp.predict-run-id nil]])
    true (merge-where [:or
                       [:= :lp.stage 1]
                       [:= :lp.stage nil]])))

(defn with-article-predict-score [m predict-run-id]
  (-> m
      (join-article-predict-values predict-run-id)
      (merge-left-join [:label :l]
                       [:= :l.label-id :lp.label-id])
      (merge-where [:or
                    [:= :l.name nil]
                    [:= :l.name "overall include"]])
      (merge-select [:lp.val :score])))

(defn select-latest-predict-run [fields]
  (-> (apply select fields)
      (from [:predict-run :pr])
      (order-by [:pr.create-time :desc])
      (limit 1)))

(defn project-latest-predict-run-id
  "Gets the most recent predict-run ID for a project."
  [project-id]
  (with-project-cache
    project-id [:predict :latest-predict-run-id]
    (-> (select-latest-predict-run [:predict-run-id])
        (merge-where [:= :project-id project-id])
        do-query first :predict-run-id)))

(defn article-latest-predict-run-id
  "Gets the most recent predict-run ID for the project of an article."
  [article-id]
  (-> (select-latest-predict-run [:predict-run-id])
      (merge-join [:project :p]
                  [:= :p.project-id :pr.project-id])
      (merge-join [:article :a]
                  [:= :a.project-id :p.project-id])
      (merge-where (if (or (string? article-id) (uuid? article-id))
                     [:= :a.article-uuid article-id]
                     [:= :a.article-id article-id]))
      do-query first :predict-run-id))

;;; article notes
(defn with-project-note [m & [note-name]]
  (cond-> m
    true (merge-join [:project-note :pn]
                     [:= :pn.project-id :p.project-id])
    note-name (merge-where [:= :pn.name note-name])))

(defn with-article-note [m & [note-name user-id]]
  (cond->
      (-> m
          (merge-join [:article-note :an]
                      [:= :an.article-id :a.article-id])
          (merge-join [:project-note :pn]
                      [:= :pn.project-note-id :an.project-note-id]))
    note-name (merge-where [:= :pn.name note-name])
    user-id (merge-where [:= :an.user-id user-id])))
