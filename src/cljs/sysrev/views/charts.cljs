(ns sysrev.views.charts
  (:require [cljsjs.chartjs]
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

(defn wrap-animate-options [options]
  (let [duration (animate-duration)]
    (merge-with merge options
                {:animation {:duration duration}
                 :hover {:animationDuration duration}
                 :responsiveAnimationDuration duration})))

(defn get-canvas-context
  "Lookup canvas context, expecting that canvas is the first child of the element with supplied id"
  [container-id]
  (-> js/document
      (.getElementById container-id)
      (.getContext "2d")))


(defn chart-container
  "Create reagent container to hold a chart. make-chart should be a function taking container-id and args."
  [make-chart height & args]
  (let [id (random-id)]
    (r/create-class
     {:reagent-render (fn [make-chart & args]
                        [:div
                         [:canvas (merge
                                   {:id id}
                                   (when height
                                     {:height height
                                      :style {:height height}}))]])
      :component-will-update (fn [this [_ make-chart & args]]
                               (apply make-chart id args))
      :component-did-mount #(apply make-chart id args)
      :display-name "chart-container"})))


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

#_
(defn line-chart
  "Creates a line chart function expecting an id to render into.
  Accepts any number of dataseries, should have equal numbers of labels and y values
  Ex: (line-chart [<x-values>] [<A Label> <B Label>] [[<series A>] <series B>])
  TODO: Need to figure out how to space x-axis labels."
  [id xs ynames yss]
  (let [context (get-canvas-context id)
        chart-data
        {:type "line"
         :options
         (wrap-animate-options
          {:unitStepSize 10
           :scales
           {:yAxes [{:scaleLabel {:display true
                                  :labelString "Value"}}]
            :xAxes [{:display true
                     :scaleLabel {:display true
                                  :labelString "Confidence"}
                     :ticks {:maxTicksLimit 20
                             :autoSkip true
                             ;; Don't show last, usually looks funky with distribution limit
                             :callback (fn [value idx values]
                                         (if (= idx (dec (count values)))
                                           ""
                                           value))}}]}
           :responsive true})
         :data {:labels xs
                :datasets (get-datasets ynames yss)}}]
    (js/Chart. context (clj->js chart-data))))

(defn bar-chart
  [id xlabels ynames yss & [colors options]]
  (let [context (get-canvas-context id)
        datasets (get-datasets ynames yss colors)
        font-color (if (= (:ui-theme @(subscribe [:self/settings]))
                          "Dark")
                     "#dddddd" "#222222")
        chart-data
        {:type "horizontalBar"
         :data {:labels xlabels
                :datasets (->> datasets (map #(merge % {:borderWidth 1})))}
         :options (merge-with
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
                   options)}]
    (when (and context (not-empty datasets))
      (js/Chart. context (clj->js chart-data)))))

(defn pie-chart
  [id entries & [on-click]]
  (let [labels (mapv #(nth % 0) entries)
        values (mapv #(nth % 1) entries)
        colors (mapv (fn [entry default-color]
                       (if (contains? entry 2)
                         (nth entry 2) default-color))
                     entries series-colors)
        context (get-canvas-context id)
        dataset
        {:data values
         :backgroundColor colors}
        chart-data
        {:type "doughnut"
         :data {:labels labels
                :datasets [dataset]}
         :options
         (wrap-animate-options
          {:legend
           {:display false}
           #_
           {:labels {:boxWidth 30
                     :fontSize 13
                     :padding 8
                     :fontStyle (when on-click "bold")
                     :fontFamily "Arial"
                     :fontColor
                     (when on-click
                       "rgba(70, 140, 230, 0.8)")}}
           :onClick
           (when on-click
             (fn [event elts]
               (let [elts (-> elts js->clj)]
                 (when (and (coll? elts) (not-empty elts))
                   (when-let [idx (-> elts first (aget "_index"))]
                     (on-click idx))))))})}]
    (when (and context (not-empty values))
      (js/Chart. context (clj->js chart-data)))))

(defn label-count->chart-height
  "Given a label count n, return the chart height in px"
  [n]
  (str (+ 35 (* 15 n)) "px"))

(defn Chart
  "Create a chart using chart-options and title with optional canvas dimensions.
  Canvas dimension are of the form {:height <number> :width <number>}"
  [chart-options title & [{:keys [height width]}]]
  (let [id (random-id)
        chart (r/atom nil)
        draw-chart-fn (fn [chart-options]
                        (reset! chart
                                (js/Chart.
                                 (get-canvas-context id)
                                 (clj->js chart-options))))
        canvas-height (r/atom nil)
        canvas-width (r/atom nil)]
    (r/create-class
     {:reagent-render
      (fn [{:keys [chart-options]} title]
        [:div.ui.segment
         [:h4.ui.dividing.header
          [:div.ui.two.column.middle.aligned.grid
           [:div.ui.left.aligned.column
            title]]]
         [:div
          [:canvas
           (cond-> {:id id}
             ;; set the heights
             @canvas-height (merge {:height @canvas-height})
             @canvas-width  (merge {:width @canvas-width})
             ;; set the styles
             (or @canvas-height
                 @canvas-width)
             (merge {:style (cond-> {}
                              @canvas-height (merge {:height @canvas-height})
                              @canvas-width (merge {:width @canvas-width}))}))]]])
      :component-will-mount
      (fn [this]
        (reset! canvas-height height)
        (reset! canvas-width width))
      :component-did-mount
      (fn [this]
        (draw-chart-fn chart-options))
      :component-will-update
      (fn [this [_ chart-options title {:keys [height width]}]]
        (.destroy @chart)
        ;; If the correct height was determinable
        ;; (including the height of the legend)
        ;; this would work in combination with
        ;; label-count->chart-height
        ;;
        ;;(reset! canvas-height height)
        ;;(reset! canvas-width width)
        (draw-chart-fn chart-options))
      :display-name (str (gensym title))})))
