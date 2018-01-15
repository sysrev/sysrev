(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.routes :as routes]
            [sysrev.util :refer [full-size? mobile?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu dropdown-menu]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.create-project :refer [CreateProject]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def state (r/atom {:create-project nil}))

(defn project-page-menu []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 1) first)
        {:keys [total]}
        @(subscribe [:project/article-counts])]
    [primary-tabbed-menu
     [{:tab-id :project
       :content "Project"
       :action [:project :project]}
      {:tab-id :user
       :content "My Labels"
       :action [:project :user]}
      (when (> total 0)
        {:tab-id :review
         :content "Review Articles"
         :action [:project :review]})]
     active-tab
     "project-menu"]))

(defn project-submenu-full []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]}
        @(subscribe [:project/article-counts])]
    [secondary-tabbed-menu
     [(when (> count 0)
        {:tab-id :overview
         :content "Overview"
         :action [:project :project :overview]}
        {:tab-id :articles
         :content "Article Activity"
         :action [:project :project :articles]})

      {:tab-id :add-articles
       :content "Add Articles"
       :action [:project :project :add-articles]}
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

      (when (> count 0)
        {:tab-id :export-data
         :content [:span "Export " [:i.ui.download.icon]]
         :action [:project :project :export-data]})

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
  (if (mobile?)
    [project-submenu-mobile]
    [project-submenu-full]))

(defn project-header [left-content right-content]
  [:div.ui.top.attached.segment.project-header
   [:div.ui.middle.aligned.grid
    [:div.row
     [:div.twelve.wide.column.project-title
      [:span.project-title left-content]]
     [:div.four.wide.right.aligned.column
      right-content]]]])


(defmethod panel-content [:project] []
  (fn [child]
    (if-not (empty? @(subscribe [:self/projects]))
      (with-loader [[:project]] {}
        (let [project-name @(subscribe [:project/name])
              admin? @(subscribe [:user/admin?])
              projects @(subscribe [:self/projects])
              {:keys [total]}
              @(subscribe [:project/article-counts])
              article-counts-loading? @(subscribe [:loading? [[:project/article-counts]]])]
          (when (and (not (nil? total))
                     (<= total 0))
            (routes/nav-scroll-top "/project/add-articles"))
          [:div
           [project-header
            project-name
            [:div
             [:a.ui.tiny.button
              {:on-click #(dispatch [:navigate [:select-project]])
               :class (if (or admin? (< 1 (count projects)))
                        "" "disabled")}
              "Change"]]]
           [:div.ui.bottom.attached.segment.project-segment
            [project-page-menu]
            [:div child]]]))
      [CreateProject state])))

(defmethod panel-content [:project :project] []
  (fn [child]
    [:div
     [project-submenu]
     child]))
