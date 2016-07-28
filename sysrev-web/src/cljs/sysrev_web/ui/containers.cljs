(ns sysrev-web.ui.containers
  (:require [sysrev-web.ajax :refer [data-initialized?]]))

(defn loading-screen []
  (fn []
    [:div.ui.container
     [:div.ui.stripe {:style {:padding-top "20px"}}
      [:h1.ui.header.huge.center.aligned "Loading data..."]]]))

(defn page-container [page]
  (fn [page]
    [:div.ui.grid.container
     [:div.row
      page]]))


(defn get-page [key comp & args]
  (if (data-initialized? key)
    [page-container [into [comp] args]]
    [loading-screen]))


(defn center-page [header child]
  [:div.ui.fluid.grid.container
   [:div.three.column.centered.row
    [:div.column.segment
     header
     child]]])
