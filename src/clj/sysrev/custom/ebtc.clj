(ns sysrev.custom.ebtc
  (:require [sysrev.db.labels :as labels]
            [clojure.math.combinatorics :as combo]
            [clojure-csv.core :as csv]
            [sysrev.shared.util :refer [in?]]
            [sysrev.db.queries :as q]))

(defn included-article-label-counts [project-id label-ids]
  (let [articles (labels/project-included-articles project-id)
        combos
        (->> label-ids
             (mapv #(->> (labels/label-possible-values %)
                         (concat [nil]) distinct))
             (apply combo/cartesian-product))
        get-article-combos
        (fn [article-id]
          (let [article (get articles article-id)
                lvalues (->> label-ids
                             (map (fn [label-id]
                                    (->> (get-in article [:labels label-id])
                                         (map :answer)
                                         (map #(if (sequential? %) % [%]))
                                         (apply concat)
                                         distinct)))
                             (map #(if (empty? %) [nil] %)))]
            (apply combo/cartesian-product lvalues)))
        article-combos
        (->> (keys articles)
             (map (fn [article-id]
                    [article-id (get-article-combos article-id)]))
             (apply concat)
             (apply hash-map))
        combo-counts
        (->> combos
             (map (fn [cvals]
                    (let [ccount
                          (->> (vals article-combos)
                               (filter #(in? % cvals))
                               count)]
                      [cvals ccount])))
             (apply concat)
             (apply hash-map))]
    (->> combos (map #(vector % (get combo-counts %))))))

(defn export-included-article-label-counts [project-id label-ids]
  (let [counts
        (->> (included-article-label-counts project-id label-ids)
             (map (fn [[lvalues count]]
                    [(map #(if (nil? %) "<not labeled>" %) lvalues)
                     count])))
        label-names
        (->> label-ids (map #(:name (q/query-label-where
                                     project-id [:= :label-id %] [:name]))))
        titles
        (concat label-names ["article_count"])
        output
        (->> counts (map (fn [[lvalues lcount]]
                           (concat (map str lvalues) [(str lcount)]))))]
    (csv/write-csv (concat [titles] output) :force-quote true)))
