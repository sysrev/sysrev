(ns sysrev.charts.chartjs
  (:require ["chart.js" :refer [Chart]]
            [reagent.core :as r]
            [reagent.dom :refer [dom-node]]))

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

(defn- chart-component [{:keys [type data options width height]}]
  (let [ref (atom nil)]
    (r/create-class
     {:constructor
      (fn [this _props]
        (swap! (r/state-atom this) assoc :chart-instance nil))
      :component-did-mount
      (fn [this]
        (render-chart this))
      :component-did-update
      (fn [this]
        (let [{:keys [chart-instance]} (r/state this)]
          (some-> chart-instance (.destroy))
          (render-chart this)))
      :should-component-update
      (fn [_this old-argv new-argv]
        (not= old-argv new-argv))
      :component-will-unmount
      (fn [this]
        (let [{:keys [chart-instance]} (r/state this)]
          (some-> chart-instance (.destroy))))
      :render
      (fn [this]
        (let [{:keys [width height]} (r/props this)]
          ;; Method of getting React ref value with Reagent taken from:
          ;; https://gist.github.com/pesterhazy/4d9df2edc303e5706d547aeabe0e17e1
          [:div {:style (when height {:height (str height "px")})}
           [:div.chart-container
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
  [chart-component (merge props {:type "horizontalBar"})])
(defn radar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "radar"})])
(defn polar [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "polar-area"})])
(defn bubble [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "bubble"})])
(defn scatter [{:keys [data options width height] :as props}]
  [chart-component (merge props {:type "scatter"})])
