(ns sysrev.views.charts
  (:require [sysrev.charts.chartjs :as chartjs]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.util :refer [random-id mobile?]]))

;; Paul Tol colors: https://personal.sron.nl/~pault/
;; This vector was copied from: https://github.com/google/palette.js/blob/master/palette.js
;; (it is under an MIT license)
;;
;; A working demo of color selections: http://google.github.io/palette.js/
;;
;; which in turn is a reproduction of Paul Tol's work at: https://personal.sron.nl/~pault/colourschemes.pdf
;;
;; Paul developed this palette for scientific charts to clearly differentiate colors and to be color-blind
;; safe
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

(defn animate-duration []
  (if (mobile?) 0 1000))

(defn wrap-animate-options [options & [duration]]
  (let [duration (or duration (animate-duration))]
    (merge-with merge
                {:animation {:duration duration}
                 :hover {:animationDuration duration}
                 :responsiveAnimationDuration duration}
                options)))

(defn wrap-disable-animation [options]
  (wrap-animate-options options 0))

(def series-colors ["rgba(29,252,35,0.4)"   ;light green
                    "rgba(252,35,29,0.4)"   ;red
                    "rgba(35,29,252,0.4)"   ;blue
                    "rgba(146,29,252,0.4)"
                    "rgba(29,252,146,0.4)"
                    "rgba(252,146,29,0.4)"
                    "rgba(183,29,252,0.4)"
                    "rgba(252,29,208,0.4)"
                    "rgba(252,183,29,0.4)"
                    "rgba(208,252,29,0.4)"
                    "rgba(29,252,183,0.4)"
                    "rgba(29,208,252,0.4)"])

(defn get-datasets
  ([ynames yss colors]
   (->> (mapv vector colors ynames yss)
        (mapv (partial zipmap [:backgroundColor :label :data]))))
  ([ynames yss] (get-datasets ynames yss series-colors)))

(defn bar-chart
  [height xlabels ynames yss & [colors options]]
  (let [datasets (get-datasets ynames yss colors)
        font-color (if (= (:ui-theme @(subscribe [:self/settings]))
                          "Dark")
                     "#dddddd" "#222222")
        data {:labels xlabels
              :datasets (->> datasets (map #(merge % {:borderWidth 1})))}
        options (merge-with
                 (fn [x1 x2]
                   (if (or (map? x1) (map? x2))
                     (merge x1 x2) x2))
                 (wrap-animate-options
                  {:scales
                   {:xAxes [{:stacked true
                             :ticks {:fontColor font-color}
                             :scaleLabel {:fontColor font-color}}]
                    :yAxes [{:stacked true
                             :ticks {:fontColor font-color}
                             :scaleLabel {:fontColor font-color}}]}
                   :legend {:labels {:fontColor font-color}}})
                 options)]
    [chartjs/horizontal-bar
     {:data data
      :height height
      :options options}]))

(defn pie-chart
  [entries & [on-click]]
  (let [labels (mapv #(nth % 0) entries)
        values (mapv #(nth % 1) entries)
        colors (mapv (fn [entry default-color]
                       (if (contains? entry 2)
                         (nth entry 2) default-color))
                     entries series-colors)
        dataset
        {:data values
         :backgroundColor colors}
        data {:labels labels
              :datasets [dataset]}
        options (wrap-animate-options
                 {:legend {:display false}
                  :onClick
                  (when on-click
                    (fn [event elts]
                      (let [elts (-> elts js->clj)]
                        (when (and (coll? elts) (not-empty elts))
                          (when-let [idx (-> elts first (aget "_index"))]
                            (on-click idx))))))})]
    [chartjs/doughnut {:data data
                       :options options
                       :height 300}]))

(defn label-count->chart-height
  "Given a label count n, return the chart height in px"
  [n]
  (+ 50 (* 12 n)))
