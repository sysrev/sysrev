(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu dropdown-menu]]
            [sysrev.views.base :refer [panel-content]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn project-page-menu []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 1) first)]
    [primary-tabbed-menu
     [{:tab-id :project
       :content "Project"
       :action [:project :project]}
      {:tab-id :user
       :content "My Labels"
       :action [:project :user]}
      {:tab-id :review
       :content "Review Articles"
       :action [:project :review]}]
     active-tab
     "project-menu"]))

(defn project-submenu-full []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 2) first)]
    [secondary-tabbed-menu
     [{:tab-id :overview
       :content "Overview"
       :action [:project :project :overview]}
      {:tab-id :articles
       :content "Article Activity"
       :action [:project :project :articles]}
      {:tab-id :labels
       :content "Label Definitions"
       :action [:project :project :labels]}
      (when false
        {:tab-id :predict
         :content "Prediction"
         :action [:project :project :predict]})]
     [{:tab-id :invite-link
       :content [:span "Invite Link " [:i.ui.mail.outline.icon]]
       :action [:project :project :invite-link]}
      {:tab-id :export-data
       :content [:span "Export " [:i.ui.download.icon]]
       :action [:project :project :export-data]}
      {:tab-id :settings
       :content [:span "Settings " [:i.ui.settings.icon]]
       :action [:project :project :settings]}]
     active-tab
     "project-menu-2"
     false]))

(defn project-submenu-mobile []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 2) first)]
    [secondary-tabbed-menu
     [{:tab-id :overview
       :content "Overview"
       :action [:project :project :overview]}
      {:tab-id :articles
       :content "Articles"
       :action [:project :project :articles]}
      {:tab-id :labels
       :content "Labels"
       :action [:project :project :labels]}
      (when false
        {:tab-id :predict
         :content "Predict"
         :action [:project :project :predict]})]
     [{:tab-id :invite-link
       :content [:span "Invite Link " [:i.ui.mail.outline.icon]]
       :action [:project :project :invite-link]}
      {:tab-id :export-data
       :content [:span "Export " [:i.ui.download.icon]]
       :action [:project :project :export-data]}
      {:tab-id :settings
       :content [:span "Settings " [:i.ui.settings.icon]]
       :action [:project :project :settings]}]
     active-tab
     "project-menu-2"
     true]))

(defn project-submenu []
  (if (full-size?)
    [project-submenu-full]
    [project-submenu-mobile]))

(defn project-header [left-content right-content]
  [:div.ui.top.attached.segment.project-header
   [:div.ui.middle.aligned.grid
    [:div.row
     [:div.twelve.wide.column.project-title
      left-content]
     [:div.four.wide.right.aligned.column
      right-content]]]])

(defmethod panel-content [:project] []
  (fn [child]
    (with-loader [[:project]] {}
      (let [project-name @(subscribe [:project/name])
            admin? @(subscribe [:user/admin?])
            project-ids @(subscribe [:user/project-ids])]
        [:div
         [project-header
          project-name
          [:div
           [:a.ui.tiny.button
            {:on-click #(dispatch [:navigate [:select-project]])
             :class (if (or admin? (< 1 (count project-ids)))
                      "" "disabled")}
            "Change"]]]
         [:div.ui.bottom.attached.segment.project-segment
          [project-page-menu]
          [:div child]]]))))

(defmethod panel-content [:project :project] []
  (fn [child]
    [:div
     [project-submenu]
     child]))
