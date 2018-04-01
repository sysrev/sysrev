(ns sysrev.views.panels.project.common
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile? nbsp]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu dropdown-menu]]))

(defn project-submenu-full []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]}
        @(subscribe [:project/article-counts])
        action-params {:project-id project-id}]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content [:span [:i.list.icon] "Sources"]
       :action (list [:project :project :add-articles] action-params)}
      {:tab-id :labels
       :content [:span [:i.tags.icon] "Label Definitions"]
       :action (list [:project :project :labels :edit] action-params)}
      {:tab-id :invite-link
       :content [:span [:i.mail.outline.icon] "Invite Link"]
       :action (list [:project :project :invite-link] action-params)}
      (when (> total 0)
        {:tab-id :export-data
         :content [:span [:i.download.icon] "Export"]
         :action (list [:project :project :export-data] action-params)})
      {:tab-id :settings
       :content [:span [:i.configure.icon] "Settings"]
       :action (list [:project :project :settings] action-params)}]
     []
     active-tab
     "bottom attached project-menu-2"
     false]))

(defn project-submenu-mobile []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        action-params {:project-id project-id}]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content [:span #_ [:i.list.icon] "Sources"]
       :action (list [:project :project :add-articles] action-params)}
      {:tab-id :labels
       :content [:span #_ [:i.tags.icon] "Labels"]
       :action (list [:project :project :labels] action-params)}
      {:tab-id :invite-link
       :content [:span #_ [:i.mail.outline.icon] "Invite Link"]
       :action (list [:project :project :invite-link] action-params)}
      {:tab-id :export-data
       :content [:span #_ [:i.download.icon] "Export"]
       :action (list [:project :project :export-data] action-params)}
      {:tab-id :settings
       :content [:span #_ [:i.configure.icon] "Settings"]
       :action (list [:project :project :settings] action-params)}]
     []
     active-tab
     "bottom attached project-menu-2"
     true]))

(defn project-submenu []
  (if (mobile?)
    [project-submenu-mobile]
    [project-submenu-full]))

(defn project-page-menu []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 1) (take 2) vec)
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
        mobile? (mobile?)
        action-params {:project-id project-id}]
    (when total
      (remove
       nil?
       (list
        ^{:key [:project-primary-menu]}
        [primary-tabbed-menu
         [(when (> total 0)
            {:tab-id [:project :overview]
             :content [:span "Overview"]
             :action (list [:project :project :overview] action-params)})
          (when (> total 0)
            {:tab-id [:project :articles]
             :content [:span
                       (when-not mobile?
                         [:span
                          [:i.icons
                           [:i.text.file.outline.icon]
                           [:i.corner.tag.icon]]
                          " "])
                       "Articles"]
             :action (list [:project :project :articles] action-params)})
          (when (> total 0)
            {:tab-id [:user :labels]
             :content [:span
                       (when-not mobile?
                         [:span
                          [:i.icons
                           [:i.user.icon]
                           [:i.corner.tag.icon]]
                          " "])
                       (if mobile?
                         "Answers" "Saved Answers")]
             :action (list [:project :user :labels] action-params)})
          (when false
            {:tab-id :predict
             :content "Prediction"
             :action (list [:project :project :predict] action-params)})
          (when (> total 0)
            {:tab-id [:review]
             :content [:span
                       (when-not mobile?
                         [:span
                          [:i.write.square.icon]])
                       "Review Articles"]
             :action (list [:project :review] action-params)
             :class "review-articles"})]
         [{:tab-id :manage
           :content (if mobile?
                      [:span [:i.settings.icon]]
                      [:span [:i.settings.icon] "Manage"])
           :action (list [:project :project :add-articles] action-params)}]
         active-tab
         (if manage?
           "attached project-menu"
           "bottom attached project-menu")
         mobile?]
        (when manage?
          ^{:key [:project-manage-menu]}
          [project-submenu]))))))
