(ns sysrev.views.panels.project.common
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile? nbsp]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.views.components :refer
             [primary-tabbed-menu secondary-tabbed-menu dropdown-menu]]
            [sysrev.state.nav :refer [project-uri]]))

(defn project-submenu-full []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]} @(subscribe [:project/article-counts])
        action-params {:project-id project-id}
        member? @(subscribe [:self/member? project-id])]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content [:span [:i.list.icon] "Sources"]
       :action (project-uri project-id "/add-articles")
       #_ (list [:project :project :add-articles] action-params)}
      {:tab-id :labels
       :content [:span [:i.tags.icon] "Label Definitions"]
       :action (project-uri project-id "/labels/edit")
       #_ (list [:project :project :labels :edit] action-params)}
      (when member?
        {:tab-id :invite-link
         :content [:span [:i.mail.outline.icon] "Invite Link"]
         :action (project-uri project-id "/invite-link")
         #_ (list [:project :project :invite-link] action-params)})
      (when (> total 0)
        {:tab-id :export-data
         :content [:span [:i.download.icon] "Export"]
         :action (project-uri project-id "/export")
         #_ (list [:project :project :export-data] action-params)})
      {:tab-id :settings
       :content [:span [:i.configure.icon] "Settings"]
       :action (project-uri project-id "/settings")
       #_ (list [:project :project :settings] action-params)}
      {:tab-id :support
       :content [:span [:i.dollar.sign.icon] "Support"]
       :action (project-uri project-id "/support")}]
     []
     active-tab
     "bottom attached project-menu-2"
     false]))

(defn project-submenu-mobile []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]} @(subscribe [:project/article-counts])
        action-params {:project-id project-id}
        member? @(subscribe [:self/member? project-id])]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content [:span #_ [:i.list.icon] "Sources"]
       :action (list [:project :project :add-articles] action-params)}
      {:tab-id :labels
       :content [:span #_ [:i.tags.icon] "Labels"]
       :action (list [:project :project :labels :edit] action-params)}
      (when member?
        {:tab-id :invite-link
         :content [:span #_ [:i.mail.outline.icon] "Invite Link"]
         :action (list [:project :project :invite-link] action-params)})
      (when (> total 0)
        {:tab-id :export-data
         :content [:span #_ [:i.download.icon] "Export"]
         :action (list [:project :project :export-data] action-params)})
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
                             [:project :settings]
                             [:project :support]]
                            active-tab)
                     :manage active-tab)
        manage? (= active-tab :manage)
        {:keys [total]}
        @(subscribe [:project/article-counts])
        mobile? (mobile?)
        action-params {:project-id project-id}
        member? @(subscribe [:self/member? project-id])]
    (when total
      (remove
       nil?
       (list
        ^{:key [:project-primary-menu]}
        [primary-tabbed-menu
         [(when (> total 0)
            {:tab-id [:project :overview]
             :content [:span "Overview"]
             :action (project-uri project-id "")
             #_ (list [:project :project :overview] action-params)})
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
             :action (project-uri project-id "/articles")
             #_ (list [:project :project :articles] action-params)})
          (when (and member? (> total 0))
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
             :action (project-uri project-id "/user")
             #_ (list [:project :user :labels] action-params)})
          (when (and member? (> total 0))
            {:tab-id [:review]
             :content [:span
                       (when-not mobile?
                         [:span
                          [:i.write.square.icon]])
                       "Review Articles"]
             :action (project-uri project-id "/review")
             #_ (list [:project :review] action-params)
             :class "review-articles"})]
         [{:tab-id :manage
           :content (if mobile?
                      [:span [:i.settings.icon]]
                      [:span [:i.settings.icon] "Manage"])
           :action (project-uri project-id "/manage")
           #_ (list [:project :project :add-articles] action-params)}]
         active-tab
         (if manage?
           "attached project-menu"
           "bottom attached project-menu")
         mobile?]
        (when manage?
          ^{:key [:project-manage-menu]}
          [project-submenu]))))))

(defn ReadOnlyMessage [text & [message-closed-atom]]
  (when (and (not (or @(subscribe [:member/admin?])
                      @(subscribe [:user/admin?])))
             (not (and message-closed-atom @message-closed-atom)))
    [:div.ui.icon.message.read-only-message
     [:i.lock.icon]
     (when message-closed-atom
       [:i {:class "close icon"
            :on-click #(do (reset! message-closed-atom true))}])
     [:div.content
      [:div.header "Read-Only View"]
      [:p text]]]))
