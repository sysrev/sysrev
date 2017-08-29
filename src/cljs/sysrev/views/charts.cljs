(ns sysrev.views.charts
  (:require [cljsjs.chartjs]
            [reagent.core :as r]
            [sysrev.util :refer [random-id]]))

(defn get-canvas-context
  "Lookup canvas context, expecting that canvas is the first child of the element with supplied id"
  [container-id]
  (-> js/document
      (.getElementById container-id)
      (.getContext "2d")))


(defn chart-container
  "Create reagent container to hold a chart. make-chart should be a function taking container-id and args."
  [make-chart & args]
  (let [id (random-id)]
    (r/create-class
     {:reagent-render (fn [make-chart & args]
                        [:div
                         [:canvas {:id id}]])
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
   (->> (mapv vector series-colors ynames yss)
        (mapv (partial zipmap [:backgroundColor :label :data]))))
  ([ynames yss] (get-datasets ynames yss series-colors)))


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
          :responsive true}
         :data {:labels xs
                :datasets (get-datasets ynames yss)}}]
    (js/Chart. context (clj->js chart-data))))


(defn bar-chart
  [id xs ynames yss]
  (let [context (get-canvas-context id)
        datasets (->> (mapv vector series-colors ynames yss)
                      (mapv (partial zipmap [:backgroundColor :label :data])))
        chart-data {:type "bar"
                    :data {:labels xs
                           :datasets (get-datasets ynames yss)}}]
    (js/Chart. context (clj->js chart-data))))

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
                      "rgba(70, 140, 230, 0.8)")}
           :onClick
           (when on-click
             (fn [event item]
               (when-let [idx (.-index item)]
                 (on-click idx))))}
          :onClick
          (when on-click
            (fn [event elts]
              (let [elts (-> elts js->clj)]
                (when (and (coll? elts) (not-empty elts))
                  (when-let [idx (-> elts first (aget "_index"))]
                    (on-click idx))))))}}]
    (js/Chart. context (clj->js chart-data))))
