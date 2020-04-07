(ns sysrev.views.panels.project.common
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.core :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.util :refer [in? mobile?]]))

(defn admin? []
  (or @(subscribe [:member/admin?])
      @(subscribe [:user/admin?])))

(def beta-compensation-users #{"eliza.grames@uconn.edu"})

(defn beta-compensation-user? [email]
  (contains? beta-compensation-users email))

(defn project-submenu-full []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]} @(subscribe [:project/article-counts])]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content [:span [:i.list.icon] "Sources"]
       :action (project-uri project-id "/add-articles")}
      {:tab-id :labels
       :content [:span [:i.tags.icon] "Label Definitions"]
       :action (project-uri project-id "/labels/edit")}
      (when (> total 0)
        {:tab-id :export-data
         :content [:span [:i.download.icon] "Export"]
         :action (project-uri project-id "/export")})
      (when (and (admin?)
                 (or (re-matches #".*@insilica.co" @(subscribe [:user/email]))
                     (beta-compensation-user? @(subscribe [:user/email]))))
        {:tab-id :compensations
         :content "Compensation"
         :action (project-uri project-id "/compensations")})
      {:tab-id :settings
       :content [:span [:i.configure.icon] "Settings"]
       :action (project-uri project-id "/settings")}]
     [#_[{:tab-id :support
          :content [:span [:i.dollar.sign.icon] "Support"]
          :action (project-uri project-id "/support")}]]
     active-tab
     "bottom attached project-menu-2"
     false]))

(defn project-submenu-mobile []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        action-params {:project-id project-id}]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content [:span "Sources"]
       :action (list [:project :project :add-articles] action-params)}
      {:tab-id :labels
       :content [:span "Labels"]
       :action (list [:project :project :labels :edit] action-params)}
      ;; disabled because no mobile interface for article list
      #_
      (when (> total 0)
        {:tab-id :export-data
         :content [:span "Export"]
         :action (list [:project :project :export-data] action-params)})
      (when (and (admin?)
                 (or (re-matches #".*@insilica.co" @(subscribe [:user/email]))
                     (beta-compensation-user? @(subscribe [:user/email]))))
        {:tab-id :compensations
         :content "Compensation"
         :action (project-uri project-id "/compensations")})
      {:tab-id :settings
       :content [:span "Settings"]
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
                             [:project :export-data]
                             [:project :settings]
                             [:project :support]
                             [:project :compensations]]
                            active-tab)
                     :manage active-tab)
        manage? (= active-tab :manage)
        {:keys [total]}
        @(subscribe [:project/article-counts])
        mobile? (mobile?)
        member? @(subscribe [:self/member? project-id])
        ready? (and (integer? total) (> total 0))
        not-ready-msg (when (not ready?) "No articles in project yet")
        not-member-msg (when (not member?) "Not a member of this project")]
    (when total
      (remove
       nil?
       (list
        ^{:key [:project-primary-menu]}
        [primary-tabbed-menu
         [{:tab-id [:project :overview]
           :class "overview"
           :content [:span "Overview"]
           :action (project-uri project-id "")
           :disabled (not ready?)
           :tooltip not-ready-msg}
          {:tab-id [:project :articles]
           :class "articles"
           :content [:span
                     (when-not mobile?
                       [:span [:i.unordered.list.icon] " "])
                     "Articles"]
           :action (project-uri project-id "/articles")
           :disabled (not ready?)
           :tooltip not-ready-msg}
          {:tab-id [:project :analytics]
           :class "analytics"
           :content [:div
                     (when-not mobile? [:span [:i.chart.bar.icon] " "])
                     "Analytics" [:sup {:style {:color "red"}} " beta"]]
           :action (project-uri project-id "/analytics")
           :disabled (not ready?)
           :tooltip not-ready-msg}
          {:tab-id [:review]
           :class "review"
           :content [:span
                     (when-not mobile?
                       [:span [:i.pencil.alternate.icon]])
                     "Review"]
           :action (project-uri project-id "/review")
           :disabled (or (not ready?) (not member?))
           :tooltip (or not-ready-msg not-member-msg)}]
         [{:tab-id :manage
           :class "manage"
           :content (if mobile?
                      [:span [:i.cog.icon]]
                      [:span [:i.cog.icon] " Manage"])
           :action (project-uri project-id "/manage")}]
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
