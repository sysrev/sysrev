(ns sysrev.db.queries
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute do-transaction sql-now to-sql-array to-jsonb]]))

;;;
;;; articles
;;;

(defn select-project-articles [project-id & [fields]]
  (-> (apply select fields)
      (from [:article :a])
      (where [:= :a.project-id project-id])))

;;;
;;; labels
;;;

(defn select-label-where [project-id where-clause & [fields]]
  (-> (apply select fields)
      (from :label)
      (where [:and
              [:= :project-id project-id]
              [:= :enabled true]])
      (merge-where where-clause)))

(defn query-label-by-name [project-id label-name & [fields]]
  (-> (select-label-where project-id [:= :name label-name] (or fields [:*]))
      do-query first))

(defn query-label-id-where [project-id where-clause]
  (-> (select-label-where project-id where-clause [:label-id])
      do-query first :label-id))

(defn label-id-from-name [project-id label-name]
  (query-label-id-where project-id [:= :name label-name]))

(defn query-project-labels [project-id & [fields]]
  (-> (select-label-where project-id true (or fields [:*]))
      do-query))

(defn label-confirmed-test [confirmed?]
  (case confirmed?
    true [:!= :confirm-time nil]
    false [:= :confirm-time nil]
    true))

(defn join-article-labels [m]
  (-> m (merge-join [:article-label :al]
                    [:= :al.article-id :a.article-id])))

(defn join-article-label-defs [m]
  (-> m (merge-join [:label :l]
                    [:= :l.label-id :al.label-id])))

(defn filter-valid-article-label [m confirmed?]
  (-> m (merge-where [:and
                      (label-confirmed-test confirmed?)
                      [:!= :al.answer nil]])))

(defn filter-label-name [m label-name]
  (-> m (merge-where [:= :l.name label-name])))

(defn filter-overall-label [m]
  (-> m (filter-label-name "overall include")))

(defn select-project-article-labels [project-id confirmed? & [fields]]
  (-> (select-project-articles project-id fields)
      (join-article-labels)
      (join-article-label-defs)
      (filter-valid-article-label confirmed?)))

;;;;
;;;; predict values
;;;;

(defn join-article-predict-values [m]
  (-> m
      (merge-join [:label-predicts :lp]
                  [:= :lp.article-id :a.article-id])))

(defn join-predict-labels [m]
  (-> m
      (merge-join [:label :l]
                  [:= :l.label-id :lp.label-id])))
