(ns sysrev.project.charts
  (:require [honeysql.helpers :as sqlh :refer [select from where group join order-by]]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.shared.charts :refer [get-color-palette palette-lookup]]
            [sysrev.util :as util :refer [sum]]))

(defn count-categorical-vals [project-id]
  (-> (select :l.label-id :l.short-label :l.value-type :o1.value :o1.count)
      (from [:label :l])
      (join [(-> (select :al.label-id :value :%count.*)
                 (from [:article-label :al]
                       [:%jsonb_array_elements.al.answer :value])
                 (where [:and
                         [:= :%jsonb_typeof.answer "array"]
                         (q/exists [:label :l] {:l.project-id project-id
                                                :l.label-id :al.label-id
                                                :l.value-type "categorical"}
                                   :where [:!= :al.confirm-time nil])])
                 (group :al.label-id :value)) :o1]
            [:= :l.label-id :o1.label-id])
      (order-by [:count :asc])
      do-query))

(defn count-boolean-vals [project-id]
  (q/find-label {:project-id project-id :value-type "boolean"}
                [:l.label-id :short-label :value-type [:al.answer :value] :%count.*]
                :with [:article-label], :where [:!= :al.confirm-time nil]
                :group [:l.label-id :short-label :value-type :value]
                :order-by [:count :asc]))

(defn process-label-counts [project-id]
  (let [ordered-counts (->> (concat (count-categorical-vals project-id)
                                    (count-boolean-vals project-id))
                            (remove #(nil? (:value %)))
                            (group-by :label-id)
                            (sort-by (fn [[_ v]] (sum (map :count v))))
                            (mapcat (fn [[_ v]] v))
                            (reverse))
        all-label-ids  (distinct (map :label-id ordered-counts))
        palette        (get-color-palette (count all-label-ids))]
    (for [{:keys [label-id] :as x} ordered-counts]
      (let [label-index (.indexOf all-label-ids label-id)]
        (merge x {:color (palette-lookup palette label-index)})))))
