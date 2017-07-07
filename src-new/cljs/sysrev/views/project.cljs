(ns sysrev.views.project
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn project-page-menu [active-panel]
  [primary-tabbed-menu
   [{:tab-id :project-overview
     :content "Project overview"
     :action "/project"}
    {:tab-id :articles
     :content "Articles"
     :action "/project/articles"}
    {:tab-id :user-profile
     :content "User"
     :action "/user"}
    {:tab-id :labels
     :content "Label definitions"
     :action "/project/labels"}
    (when false
      {:tab-id :project-prediction
       :content "Prediction"
       :action "/project/predict"})
    {:tab-id :classify
     :content [:div.ui.large.basic.button.classify "Classify"]
     :action "/project/classify"}]
   active-panel
   "project-menu"])

(defn project-wrapper [content]
  (with-loader [[:project]] {}
    (let [active-panel @(subscribe [:active-panel])
          project-name @(subscribe [:project/name])]
      (when project-name
        [:div.ui.container
         [:div.ui.top.attached.center.aligned.segment.project-header
          [:h5 (str project-name)]]
         [:div.ui.bottom.attached.segment.project-segment
          [project-page-menu active-panel]
          [:div.padded content]]]))))
