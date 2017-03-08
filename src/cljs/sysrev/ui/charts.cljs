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
         :reagent-render (fn []
                           [:div
                            [:canvas {:id id :width (first bounds) :height (second bounds)}]])})))
  ([make-chart] (chart-container [nil nil] make-chart)))

(def series-colors ["rgba(29,252,35,0.4)"
                    "rgba(252,35,29,0.4)"
                    "rgba(35,29,252,0.4)"
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
  ([ynames yss colors] (->> (mapv vector series-colors ynames yss)
                            (mapv (partial zipmap [:backgroundColor :label :data]))))
  ([ynames yss] (get-datasets ynames yss series-colors)))

(defn line-chart
  "Creates a line chart function expecting an id to render into.
  Accepts any number of dataseries, should have equal numbers of labels and y values
  Ex: (line-chart [<x-values>] [<A Label> <B Label>] [[<series A>] <series B>])
  TODO: Need to figure out how to space x-axis labels."
  [xs ynames yss]
  (fn [id]
   (let [context (.getContext (.getElementById js/document id) "2d")
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
                            :datasets (get-datasets ynames yss)}}]
    (js/Chart. context (clj->js chart-data)))))


(defn histogram
  [xs ynames yss]
  (fn [id]
    (let [context (.getContext (.getElementById js/document id) "2d")
          datasets (->> (mapv vector series-colors ynames yss)
                        (mapv (partial zipmap [:backgroundColor :label :data])))
          chart-data {:type "bar"
                      :data {:labels xs
                             :datasets (get-datasets ynames yss)}}]
      (js/Chart. context (clj->js chart-data)))))
