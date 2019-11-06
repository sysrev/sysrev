(ns sysrev.views.charts
  (:require [sysrev.charts.chartjs :as chartjs]
            [reagent.interop :refer-macros [$]]
            [re-frame.core :refer [subscribe]]
            [sysrev.util :as util]))

(defn on-graph-hover [items-clickable?]
  (fn [event elts]
    (when (-> event .-type (not= "click"))
      (let [cursor
            ;; set cursor to pointer if over a clickable item,
            ;; otherwise reset cursor to default
            (if (not items-clickable?) "default"
                (let [elts (-> elts js->clj)
                      idx (when (and (coll? elts) (not-empty elts))
                            (-> elts first (aget "_index")))]
                  (if (and (integer? idx) (>= idx 0))
                    "pointer" "default")))]
        (set! (-> event .-target .-style .-cursor) cursor)))))

(defn on-legend-hover []
  (fn [event _item]
    (set! (-> event .-target .-style .-cursor) "pointer")))

(defn graph-text-color []
  (if (= "Dark" (:ui-theme @(subscribe [:self/settings])))
    "#dddddd" "#282828"))

(defn graph-border-color []
  "rgba(128,128,128,0.2)")

(defn graph-font-family []
  "'Lato', 'Helvetica Neue', 'Helvetica', 'Arial', sans-serif")

(defn graph-font-family-alternate []
  "'Open Sans', 'Lato', 'Helvetica Neue', 'Helvetica', 'Arial', sans-serif")

(defn graph-font-settings [& {:keys [alternate]}]
  {:fontColor (graph-text-color)
   :fontFamily (if alternate (graph-font-family-alternate)
                   (graph-font-family))
   :fontSize (if (util/mobile?) 12 13)})

(defn tooltip-font-settings [& {:keys [alternate]}]
  (let [family (if alternate (graph-font-family-alternate)
                   (graph-font-family))]
    {:titleFontFamily family, :titleFontSize 13
     :bodyFontFamily family, :bodyFontSize 13
     :xPadding 8, :yPadding 7}))

(defn wrap-default-options
  [options & {:keys [animate? items-clickable?]
              :or {animate? true items-clickable? false}}]
  (let [mobile? (util/mobile?)
        duration (cond (not animate?) 0
                       mobile?        0
                       :else          1000)]
    (merge-with merge
                {:animation {:duration duration}
                 :responsiveAnimationDuration duration
                 :hover {:animationDuration (if mobile? 0 300)}
                 :legend {:onHover (on-legend-hover)}
                 :onHover (on-graph-hover items-clickable?)
                 :tooltips (tooltip-font-settings)}
                options)))

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
  [height xlabels ynames yss &
   {:keys [colors options on-click display-ticks log-scale x-label-string]
    :or {display-ticks true log-scale false}}]
  (let [font (graph-font-settings)
        datasets (get-datasets ynames yss colors)
        max-length (if (util/mobile?) 22 28)
        xlabels-short (->> xlabels (mapv #(if (<= (count %) max-length)
                                            % (str (subs % 0 (- max-length 2)) "..."))))
        data {:labels xlabels-short
              :datasets (->> datasets (map #(merge % {:borderWidth 1})))}
        options (merge-with
                 (fn [x1 x2]
                   (if (or (map? x1) (map? x2))
                     (merge x1 x2) x2))
                 (wrap-default-options
                  {:scales
                   {:xAxes [{:stacked true
                             :ticks (merge font {:display display-ticks})
                             :gridLines {:drawTicks (if display-ticks true false)
                                         :zeroLineWidth (if display-ticks 0 1)
                                         :color (graph-border-color)}
                             :type (if log-scale "logarithmic" "linear")
                             :scaleLabel (cond-> font
                                           x-label-string
                                           (merge {:display true
                                                   :labelString x-label-string}))}]
                    :yAxes [{:stacked true
                             :ticks (merge font {:padding 7})
                             :gridLines {:drawTicks false
                                         :color (graph-border-color)}}]}
                   :legend {:labels font}
                   :tooltips {:callbacks
                              {:label
                               (fn [item _data]
                                 (let [idx ($ item :datasetIndex)
                                       label (nth ynames idx)
                                       value ($ item :xLabel)
                                       value-str (if (and (number? value) (not (integer? value)))
                                                   (/ (js/Math.round (* 100 value)) 100.0)
                                                   value)]
                                   (str label ": " value-str)))}}
                   :onClick
                   (when on-click
                     (fn [_e elts]
                       (let [elts (-> elts js->clj)]
                         (when (and (coll? elts) (not-empty elts))
                           (when-let [idx (-> elts first (aget "_index"))]
                             (on-click idx))))))}
                  :items-clickable? (if on-click true false))
                 options)]
    [chartjs/horizontal-bar {:data data :height height :options options}]))

(defn pie-chart
  [entries & [on-click]]
  (let [labels (mapv #(nth % 0) entries)
        values (mapv #(nth % 1) entries)
        colors (mapv (fn [entry default-color]
                       (if (contains? entry 2)
                         (nth entry 2) default-color))
                     entries series-colors)
        dataset {:data values :backgroundColor colors}
        data {:labels labels :datasets [dataset]}
        options (wrap-default-options
                 {:legend {:display false}
                  :onClick (when on-click
                             (fn [_e elts]
                               (let [elts (-> elts js->clj)]
                                 (when (and (coll? elts) (not-empty elts))
                                   (when-let [idx (-> elts first (aget "_index"))]
                                     (on-click idx))))))}
                 :items-clickable? (boolean on-click))]
    [chartjs/doughnut {:data data :options options :height 245}]))
