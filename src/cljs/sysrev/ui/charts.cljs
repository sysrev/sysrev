(ns sysrev.ui.charts
  (:require [cljsjs.chartjs]
            [clojure.test.check.generators :as gen]
            [reagent.core :as r]))

(defn random-id
  "Generate a random-id to use for manually rendered components."
  ([len]
   (let [length (or len 6)
         char-gen (gen/fmap char (gen/one-of [(gen/choose 65 90) (gen/choose 97 122)]))]
     (apply str (gen/sample char-gen length))))
  ([] (random-id 6)))


(defn chart-container
  "Create reagent container to hold a chart. Expects chart-f to be at
  unary function taking just an id in which to render the chart.
  Without bounds provided, the chart will just expand into the available area of a block element"
  ([bounds make-chart]
   (let [id (random-id)]
     (r/create-class
       {:component-did-mount #(make-chart id)
        :display-name "chart-container"
        :reagent-render (fn [] [:canvas {:id id :width (first bounds) :height (second bounds)}])})))
  ([make-chart] (chart-container [nil 150] make-chart)))

(def series-colors ["rgba(190,66,66,0.4)" "rgba(40,66,255,0.4)"])

(defn line-chart
  "Creates a line chart function expecting an id to render into.
  Accepts any number of dataseries, should have equal numbers of labels and y values
  Ex: (line-chart [<x-values>] [<A Label> <B Label>] [[<series A>] <series B>])
  TODO: Need to figure out how to space x-axis labels."
  [xs ynames yss]
  (fn [id]
   (let [context (.getContext (.getElementById js/document id) "2d")
         datasets (->> (mapv vector series-colors ynames yss)
                       (mapv (partial zipmap [:backgroundColor :label :data])))
         chart-data {:type "line"
                     :options {:unitStepSize 10
                               :scales {:yAxes [{:scaleLabel {:display true
                                                              :labelString "Value"}}]
                                        :xAxes [{:display true
                                                 :scaleLabel {:display true
                                                              :labelString "Confidence"}}]
                                        ;:responsive true
                                        :showXLabels 10}}
                     :data {:labels xs
                            :datasets datasets}}]
    (js/Chart. context (clj->js chart-data)))))
