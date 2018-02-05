(ns sysrev.views.panels.project.common
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile?]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu dropdown-menu]]))

(defn project-submenu-full []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]}
        @(subscribe [:project/article-counts])]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content "Sources"
       :action [:project :project :add-articles]}
      {:tab-id :labels
       :content "Label Definitions"
       :action [:project :project :labels]}
      {:tab-id :invite-link
       :content [:span "Invite Link " [:i.ui.mail.outline.icon]]
       :action [:project :project :invite-link]}
      (when (> total 0)
        {:tab-id :export-data
         :content [:span "Export " [:i.ui.download.icon]]
         :action [:project :project :export-data]})
      {:tab-id :settings
       :content [:span "Settings " [:i.ui.settings.icon]]
       :action [:project :project :settings]}]
     []
     active-tab
     "bottom attached project-menu-2"
     false]))

(defn project-submenu-mobile []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 2) first)]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content "Sources"
       :action [:project :project :add-articles]}
      {:tab-id :labels
       :content "Labels"
       :action [:project :project :labels]}
      {:tab-id :invite-link
       :content [:span "Invite Link " [:i.ui.mail.outline.icon]]
       :action [:project :project :invite-link]}
      {:tab-id :export-data
       :content [:span "Export " [:i.ui.download.icon]]
       :action [:project :project :export-data]}
      {:tab-id :settings
       :content [:span "Settings " [:i.ui.settings.icon]]
       :action [:project :project :settings]}]
     []
     active-tab
     "bottom attached project-menu-2"
     true]))

(defn project-submenu []
  (if (mobile?)
    [project-submenu-mobile]
    [project-submenu-full]))

(defn project-page-menu []
  (let [active-tab (->> @(subscribe [:active-panel]) (drop 1) (take 2) vec)
        active-tab (if (in? [[:project :add-articles]
                             [:project :labels]
                             [:project :invite-link]
                             [:project :export-data]
                             [:project :settings]]
                            active-tab)
                     :manage active-tab)
        manage? (= active-tab :manage)
        {:keys [total]}
        @(subscribe [:project/article-counts])
        mobile? (mobile?)]
    (println (str "active-tab = " (pr-str active-tab)))
    (when total
      (remove
       nil?
       (list
        ^{:key [:project-primary-menu]}
        [primary-tabbed-menu
         [(when (> total 0)
            {:tab-id [:project :overview]
             :content "Overview"
             :action [:project :project :overview]})
          (when (> total 0)
            {:tab-id [:project :articles]
             :content "Articles"
             :action [:project :project :articles]})
          (when (> total 0)
            {:tab-id [:user :labels]
             :content (if mobile?
                        "Answers" "Saved Answers")
             :action [:project :user :labels]})
          (when false
            {:tab-id :predict
             :content "Prediction"
             :action [:project :project :predict]})
          (when (> total 0)
            {:tab-id [:review]
             :content "Review Articles"
             :action [:project :review]
             :class "review-articles"})]
         [{:tab-id :manage
           :content (if mobile?
                      [:span [:i.ui.settings.icon]]
                      [:span "Manage " [:i.ui.settings.icon]])
           :action [:project :project :add-articles]}]
         active-tab
         (if manage?
           "attached project-menu"
           "bottom attached project-menu")
         mobile?]
        (when manage?
          ^{:key [:project-manage-menu]}
          [project-submenu]))))))
