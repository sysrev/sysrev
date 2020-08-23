(ns sysrev.views.charts
  (:require [sysrev.charts.chartjs :as chartjs]
            [re-frame.core :refer [subscribe]]
            [sysrev.util :as util]))

(defn on-graph-hover [items-clickable?]
  (fn [^js event elts]
    (when (not= (.-type event) "click")
      (let [cursor (if (not items-clickable?) "default"
                       ;; set cursor to pointer if over a clickable item,
                       ;; otherwise reset cursor to default
                       (if (and (pos-int? (.-length elts))
                                (nat-int? (-> elts (aget 0) .-index)))
                         "pointer" "default"))]
        (set! (-> event .-native .-target .-style .-cursor) cursor)))))

(defn on-legend-hover []
  (fn [^js event _item]
    (set! (-> event .-native .-target .-style .-cursor) "pointer")))

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
  {:font {:color (graph-text-color)
          :family (if alternate (graph-font-family-alternate)
                      (graph-font-family))
          :size (if (util/mobile?) 12 13)}})

(defn tooltip-font-settings [& {:keys [alternate]}]
  (let [family (if alternate (graph-font-family-alternate)
                   (graph-font-family))]
    {:titleFontFamily family, :titleFontSize 13
     :bodyFontFamily family, :bodyFontSize 13
     :xPadding 8, :yPadding 7}))

(defn wrap-default-options [options & {:keys [animate? items-clickable?]
                                       :or {animate? true items-clickable? false}}]
  (let [mobile? (util/mobile?)
        duration (cond (not animate?) 0
                       mobile?        0
                       :else          1000)]
    (merge-with merge
                {:animation {:active (if mobile? 0 300)
                             :resize duration
                             :duration duration}
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

(defn bar-chart [height xlabels ynames yss &
                 {:keys [colors options on-click display-ticks log-scale x-label-string]
                  :or {display-ticks true log-scale false}}]
  (let [font (graph-font-settings)
        datasets (get-datasets ynames yss colors)
        max-length (if (util/mobile?) 22 28)
        xlabels-short (->> xlabels (mapv #(util/ellipsize % max-length)))
        data {:labels xlabels-short
              :datasets (->> datasets (map #(merge % {:borderWidth 1})))}
        options (merge-with
                 (fn [x1 x2]
                   (if (or (map? x1) (map? x2))
                     (merge x1 x2) x2))
                 (wrap-default-options
                  {:scales {:x (cond-> {:stacked true
                                        :ticks (merge font {:display display-ticks})
                                        :gridLines {:drawTicks (if display-ticks true false)
                                                    :zeroLineWidth (if display-ticks 0 1)
                                                    :color (graph-border-color)}
                                        :scaleLabel (cond-> font
                                                      x-label-string
                                                      (merge {:display true
                                                              :labelString x-label-string}))}
                                 log-scale (merge {:type "logarithmic"}))
                            :y {;; :scaleLabel font
                                :stacked true
                                :ticks (merge font {:padding 7})
                                :gridLines {:drawTicks false
                                            :color (graph-border-color)}}}
                   :legend {:labels font}
                   :tooltips {:mode "y"
                              :callbacks
                              {:label
                               (fn [item _data]
                                 (let [idx ^js (.-datasetIndex item)
                                       label (nth ynames idx)
                                       value ^js (.-formattedValue item)
                                       value-str (if (and (number? value) (not (integer? value)))
                                                   (/ (js/Math.round (* 100 value)) 100.0)
                                                   value)]
                                   (str label ": " value-str)))}}
                   :onClick
                   (when on-click
                     (fn [_e elts]
                       (when-let [idx (and (pos-int? (.-length elts))
                                           (-> elts (aget 0) .-index))]
                         (on-click idx))))}
                  :items-clickable? (boolean on-click))
                 options)]
    [chartjs/horizontal-bar {:data data :height height :options options}]))

(defn pie-chart [entries & [on-click]]
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
                             (fn [e elts]
                               (when (pos-int? (.-length elts))
                                 (when-let [idx (-> elts (aget 0) .-index)]
                                   (on-click idx)))))}
                 :items-clickable? (boolean on-click))]
    [chartjs/doughnut {:data data :options options :height 245}]))
