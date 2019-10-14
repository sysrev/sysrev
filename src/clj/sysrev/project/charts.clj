(ns sysrev.project.charts
  (:require [sysrev.db.core :refer [with-project-cache]]
            [sysrev.project.core :as project]
            [sysrev.label.core :as labels]
            [sysrev.shared.transit :as sr-transit]))

;; Paul Tol colors: https://personal.sron.nl/~pault/
;;
;; This vector was copied from:
;; https://github.com/google/palette.js/blob/master/palette.js (it is
;; under an MIT license)
;;
;; A working demo of color selections:
;; http://google.github.io/palette.js/
;;
;; which in turn is a reproduction of Paul Tol's work at:
;; https://personal.sron.nl/~pault/colourschemes.pdf
;;
;; Paul developed this palette for scientific charts to clearly
;; differentiate colors and to be color-blind safe
(def paul-tol-colors
  [["#4477aa"],
   ["#4477aa", "#cc6677"],
   ["#4477aa", "#ddcc77", "#cc6677"],
   ["#4477aa", "#117733", "#ddcc77", "#cc6677"],
   ["#332288", "#88ccee", "#117733", "#ddcc77", "#cc6677"],
   ["#332288", "#88ccee", "#117733", "#ddcc77", "#cc6677", "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#ddcc77", "#cc6677", "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77", "#cc6677",
    "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77", "#cc6677",
    "#882255", "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77", "#661100",
    "#cc6677", "#882255", "#aa4499"],
   ["#332288", "#6699cc", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77",
    "#661100", "#cc6677", "#882255", "#aa4499"],
   ["#332288", "#6699cc", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77",
    "#661100", "#cc6677", "#aa4466", "#882255", "#aa4499"]])

(defn label-value-counts
  "Extract the answer counts for labels for the current project"
  [article-labels project-labels]
  (let [article-labels
        (->> article-labels
             (mapv #(select-keys % [:labels :article-id])))
        extract-labels-fn
        (fn [{:keys [labels article-id]}]
          (->> labels
               (map
                (fn [[label-id entry]]
                  (let [label (get project-labels label-id)
                        short-label (or (:short-label label)
                                        (:name label))
                        value-type (:value-type label)]
                    (map
                     (partial merge
                              {:article-id article-id
                               :short-label short-label
                               :value-type value-type
                               :label-id label-id})
                     entry))))
               flatten))
        label-values
        (->> article-labels
             (map extract-labels-fn)
             flatten
             ;; categorical data should be treated as sets, not vectors
             (map (fn [{:keys [answer] :as entry}]
                    (if (sequential? answer)
                      (update entry :answer #(vec %))
                      (update entry :answer #(vector %))))))
        label-counts-fn
        (fn [[short-label entries]]
          {:short-label short-label
           :value-counts (->> entries
                              (map :answer)
                              (flatten)
                              (frequencies))
           :value-type (:value-type (first entries))
           :label-id (:label-id (first entries))})]
    (map label-counts-fn (group-by :short-label label-values))))

(defn process-label-count
  "Given a coll of public-labels, return a vector of value-count maps"
  [article-labels labels]
  (->> (label-value-counts article-labels labels)
       (map (fn [{:keys [value-counts] :as entry}]
              (map (fn [[value value-count]]
                     (merge entry {:value value
                                   :count value-count}))
                   value-counts)))
       flatten
       (into [])))

(defn short-labels-vector
  "Given a set of label-counts, get the set of short-labels"
  [processed-label-counts]
  ((comp (partial into []) sort set (partial mapv :short-label))
   processed-label-counts))

(defn processed-label-color-map
  "Given a set of label-counts, generate a color map"
  [processed-label-counts]
  (let [short-labels (short-labels-vector processed-label-counts)
        ;; need to account for the fact that this fn can handle empty datasets
        color-count (max 0 (- (count short-labels) 1))
        palette (nth paul-tol-colors color-count)
        color-map (zipmap short-labels palette)]
    (mapv (fn [label palette]
            {:short-label label :color palette})
          short-labels palette)))

(defn add-color-processed-label-counts
  "Given a processed-label-count, add color to each label"
  [processed-label-counts]
  (let [color-map (processed-label-color-map processed-label-counts)]
    (mapv #(merge % {:color (:color (first (filter (fn [m]
                                                     (= (:short-label %)
                                                        (:short-label m))) color-map)))})
          processed-label-counts)))

(defn process-label-counts [project-id]
  (with-project-cache project-id [:member-label-counts]
    (let [article-labels (vals (labels/query-public-article-labels project-id))
          labels (project/project-labels project-id)
          label-ids (into [] (keys labels))]
      (->>
       ;; get the counts of the label's values
       (process-label-count article-labels labels)
       ;; filter out labels of type string
       (filterv #(not= (:value-type %) "string"))
       ;; do initial sort by values
       (sort-by #(str (:value %)))
       ;; sort booleans such that true goes before false
       ;; sort categorical alphabetically
       ((fn [processed-public-labels]
          (let [grouped-processed-public-labels
                (group-by :value-type processed-public-labels)
                boolean-labels
                (get grouped-processed-public-labels "boolean")
                categorical-labels
                (get grouped-processed-public-labels "categorical")]
            (concat (reverse (sort-by :value boolean-labels))
                    (reverse (sort-by :count categorical-labels))))))
       ;; add color
       add-color-processed-label-counts))))