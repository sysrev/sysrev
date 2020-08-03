(ns sysrev.project.charts
  (:require [sysrev.db.core :refer [with-project-cache]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [honeysql.helpers :as sqlh :refer [select from where merge-where order-by join]]
            [sysrev.db.core :refer [do-query]]))

(defn count-categorical [project-id]
  (-> (select :la.label-id :la.short-label :la.value_type :o1.value :o1.count)(from [:label :la])
      (join
        [(-> (select :label-id,:value,:%count.*)
             (from :article-label [:%jsonb_array_elements.answer :value])
             (where [:exists (-> (select 1)(from :label)
                                 (where [:= :label.value-type "categorical"])
                                 (merge-where [:= :label.project-id project-id])
                                 (merge-where [:= :article-label.label-id :label.label-id])
                                 (merge-where :confirmed))])
             (sqlh/group :article-label.label-id :value) ) :o1]
        [:= :la.label-id :o1.label-id])
      (sqlh/order-by [:count :desc])
      do-query))

(defn count-boolean [project-id]
  (-> (select :la.label-id :la.short-label :la.value_type [:al.answer :value] :%count.*)
      (from [:label :la])
      (join [:article-label :al][:= :la.label-id :al.label-id])
      (where [:and [:= :la.value_type "boolean"][:= :la.project-id project-id]])
      (merge-where :confirmed)
      (sqlh/group :la.label-id :la.short-label :la.value_type :al.answer)
      (sqlh/order-by [:count :desc])
      do-query))

(defn process-label-counts [project-id]
  (let [catcounts  (count-categorical project-id)
        boolcounts (count-boolean project-id)
        counts     (filter #(not= (:value %) nil) (concat catcounts boolcounts))
        lbls    (distinct (map :label-id counts))
        palette (if (> (count lbls) 12)
                  (last sysrev.shared.charts/paul-tol-colors)
                  (first (filter #(>= (count %) (count lbls)) sysrev.shared.charts/paul-tol-colors)))]
    (map (fn [lblcount]
           (let [lbl-zip-index (.indexOf lbls (:label-id lblcount))]
             (merge lblcount {:color (nth palette (mod lbl-zip-index (count palette)))})))
         counts)))