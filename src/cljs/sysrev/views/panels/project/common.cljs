(ns sysrev.views.panels.project.common
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu dropdown-menu]]))

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
     [(when (> total 0)
        {:tab-id :overview
         :content "Overview"
         :action [:project :project :overview]})
      (when (> total 0)
        {:tab-id :articles
         :content "Article Activity"
         :action [:project :project :articles]})

      {:tab-id :add-articles
       :content "Sources"
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

      (when (> total 0)
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
