(ns sysrev.db.queries
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute do-transaction sql-now
              to-sql-array to-jsonb sql-field
              with-project-cache clear-project-cache
              with-query-cache clear-query-cache]]
            [sysrev.util :refer [in?]]))

;;;
;;; articles
;;;

(defn select-project-articles
  [project-id fields & [{:keys [include-disabled? tname]
                         :or {include-disabled? false
                              tname :a}
                         :as opts}]]
  (cond->
      (-> (apply select fields)
          (from [:article tname]))
    project-id (merge-where [:= (sql-field tname :project-id) project-id])
    (not include-disabled?) (merge-where [:= (sql-field tname :enabled) true])))

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
   [:= (sql-field tname :article-id) article-id]
   fields
   {:include-disabled? include-disabled?
    :tname tname}))

(defn query-article-by-id [article-id fields]
  (-> (select-article-by-id article-id fields)
      do-query first))

(defn query-article-locations-by-id [article-id fields]
  (-> (apply select fields)
      (from [:article-location :al])
      (where [:= :al.article-id article-id])
      do-query))

;;;
;;; labels
;;;

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

(defn query-label-id-where [project-id where-clause]
  (-> (select-label-where project-id where-clause [:label-id])
      do-query first :label-id))

(defn label-id-from-name [project-id label-name]
  (query-label-id-where project-id [:= :name label-name]))

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

(defn filter-valid-article-label [m confirmed?]
  (-> m (merge-where [:and
                      (label-confirmed-test confirmed?)
                      [:!= :al.answer nil]
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

(defn join-article-predict-values [m & [predict-run-id stage]]
  (cond-> m
    true (merge-join [:label-predicts :lp]
                     [:= :lp.article-id :a.article-id])
    predict-run-id (merge-where [:= :lp.predict-run-id predict-run-id])
    stage (merge-where [:= :lp.stage stage])))

(defn join-predict-labels [m]
  (-> m
      (merge-join [:label :l]
                  [:= :l.label-id :lp.label-id])))

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
  (with-query-cache
    [:predict :article article-id :latest-predict-run-id]
    (-> (select-latest-predict-run [:predict-run-id])
        (merge-join [:project :p]
                    [:= :p.project-id :pr.project-id])
        (merge-join [:article :a]
                    [:= :a.project-id :p.project-id])
        (merge-where [:= :a.article-id article-id])
        do-query first :predict-run-id)))

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

;;;
;;; combined
;;;

(defn with-article-predict-score [m predict-run-id]
  (-> m
      (join-article-predict-values predict-run-id 1)
      (join-predict-labels)
      (filter-overall-label)
      (merge-select [:lp.val :score])))

(defn query-article-by-id-full
  "Queries for an article ID with data from other tables included."
  [article-id & [{:keys [predict-run-id include-disabled?]
                  :or {include-disabled? false}}]]
  (with-query-cache
    [:article article-id :full [predict-run-id include-disabled?]]
    (->>
     (pvalues
      (-> (select-article-by-id
           article-id [:a.*] {:include-disabled? include-disabled?})
          (with-article-predict-score
            (or predict-run-id (article-latest-predict-run-id article-id)))
          do-query first)
      {:locations
       (->> (query-article-locations-by-id
             article-id [:source :external-id])
            (group-by :source))})
     (apply merge))))
