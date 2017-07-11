(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.views.base :refer [panel-content]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn project-page-menu []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 1) first)]
    [primary-tabbed-menu
     [{:tab-id :project
       :content "Project"
       :action "/project"}
      {:tab-id :member-account
       :content "My Labels"
       :action "/project/user"}
      {:tab-id :classify
       :content "Classify Articles"
       :action "/project/classify"}]
     active-tab
     "project-menu"]))

(defn project-submenu []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 2) first)]
    [secondary-tabbed-menu
     [{:tab-id :overview
       :content "Overview"
       :action "/project"}
      {:tab-id :articles
       :content "Article Activity"
       :action "/project/articles"}
      {:tab-id :labels
       :content "Label Definitions"
       :action "/project/labels"}
      (when false
        {:tab-id :predict
         :content "Prediction"
         :action "/project/predict"})
      {:tab-id :settings
       :content "Settings"
       :action "/project/settings"}]
     active-tab
     "project-menu-2"]))

(defmethod panel-content [:project] []
  (fn [child]
    (with-loader [[:project]] {}
      (let [project-name @(subscribe [:project/name])]
        [:div.ui.container
         [:div.ui.top.attached.center.aligned.segment.project-header
          [:h5 (str project-name)]]
         [:div.ui.bottom.attached.segment
          [project-page-menu]
          child]]))))

(defmethod panel-content [:project :project] []
  (fn [child]
    [:div
     [project-submenu]
     child]))
