(ns sysrev.charts.chartjs
  (:require [cljsjs.chartjs]
            [reagent.core :as r]))

;; React component implementation based on core functionality from:
;; https://github.com/jerairrest/react-chartjs-2/blob/master/src/index.js

(defn- render-chart [chart]
  (let [{:keys [type data options width height]} (r/props chart)]
    (swap! (r/state-atom chart) assoc
           :chart-instance
           (js/Chart. (r/dom-node chart)
                      (clj->js {:type type
                                :data data
                                :options options})))))

(defn- chart-component [{:keys [type data options width height]}]
  (let [ref (atom nil)]
    (r/create-class
     {:component-will-mount
      (fn [this]
        (swap! (r/state-atom this) assoc
               :chart-instance nil))

      :component-did-mount
      (fn [this]
        (render-chart this))

      :component-did-update
      (fn [this]
        (let [{:keys [chart-instance]} (r/state this)]
          (when chart-instance
            (.destroy chart-instance))
          (render-chart this)))

      :should-component-update
      (fn [this old-argv new-argv]
        (not= old-argv new-argv))

      :component-will-unmount
      (fn [this]
        (let [{:keys [chart-instance]} (r/state this)]
          (when chart-instance
            (.destroy chart-instance))))

      :render
      (fn [this]
        (let [{:keys [type data options width height]} (r/props this)]
          ;; Method of getting React ref value with Reagent taken from:
          ;; https://gist.github.com/pesterhazy/4d9df2edc303e5706d547aeabe0e17e1
          [:canvas (cond-> {:ref #(reset! ref %)}
                     height (merge {:height height})
                     width  (merge {:width width}))]))})))

(defn doughnut [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "doughnut"})])
(defn pie [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "pie"})])
(defn line [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "line"})])
(defn bar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "bar"})])
(defn horizontal-bar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "horizontalBar"})])
(defn radar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "radar"})])
(defn polar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "polar-area"})])
(defn bubble [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "bubble"})])
(defn scatter [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "scatter"})])
