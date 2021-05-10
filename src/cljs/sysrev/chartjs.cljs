(ns sysrev.chartjs
  (:require ["chart.js" :as Chart]
            [clojure.walk :as walk]
            [reagent.core :as r]
            [reagent.dom :refer [dom-node]]
            [sysrev.util :as util]))

(def defaults Chart/defaults)

;; React component implementation based on core functionality from:
;; https://github.com/jerairrest/react-chartjs-2/blob/master/src/index.js

(defn- render-chart [chart-elt]
  (let [{:keys [type data options]} (r/props chart-elt)]
    (swap! (r/state-atom chart-elt) assoc
           :chart-instance
           (Chart. (-> (dom-node chart-elt) .-firstChild .-firstChild)
                   (clj->js {:type type, :data data
                             :options (merge {:responsive true
                                              :maintainAspectRatio false}
                                             options)})))))

(defn- chart-equality-inputs
  "Converts chart component props to a map that can be used for an
  equality test to determine if the reagent component needs to be
  updated.

  This is needed to ensure the map contains no function values, which
  will test as unequal whenever a parent component is updated and
  generates new anonymous function references."
  [chart-props]
  (walk/postwalk #(when-not (fn? %) %) chart-props))

(defn- chart-component [{:keys [type data options width height]}]
  (let [ref (atom nil)]
    (r/create-class
     {:constructor              #(swap! (r/state-atom %) assoc :chart-instance nil)
      :component-did-mount      #(render-chart %)
      :component-did-update     #(do (some-> (r/state %) :chart-instance (.destroy))
                                     (render-chart %))
      :component-will-unmount   #(some-> (r/state %) :chart-instance (.destroy))
      :should-component-update  (fn [_this [_ old-props] [_ new-props]]
                                  (not= (chart-equality-inputs old-props)
                                        (chart-equality-inputs new-props)))
      :render
      (fn [this]
        (let [{:keys [width height]} (r/props this)]
          ;; Method of getting React ref value with Reagent taken from:
          ;; https://gist.github.com/pesterhazy/4d9df2edc303e5706d547aeabe0e17e1
          [:div {:style (when height {:height (str height "px")})}
           [:div.chart-container.noselect
            {:style {:position "relative"
                     :height (when height (str height "px"))
                     :width (when width (str width "px"))}}
            [:canvas {:ref #(reset! ref %)}]]]))})))

(defn doughnut [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "doughnut"})])
(defn pie [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "pie"})])
(defn line [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "line"})])
(defn bar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "bar"})])
(defn horizontal-bar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "bar" :options (merge options {:indexAxis :y})})])
(defn radar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "radar"})])
(defn polar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "polar-area"})])
(defn bubble [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "bubble"})])
(defn scatter [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "scatter"})])
