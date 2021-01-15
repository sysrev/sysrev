(ns sysrev.views.panels.project.common
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.core :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.util :as util :refer [in?]]))

(def beta-compensation-users #{"eliza.grames@uconn.edu"})

(defn beta-compensation-user? [email]
  (contains? beta-compensation-users email))

(defn- analytics-submenu []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) last)]
    [secondary-tabbed-menu
     [{:tab-id :concordance
       :content [:span  "Concordance"]
       :action (project-uri project-id "/analytics/concordance")}
      {:tab-id :labels
       :content [:span  "Labels"]
       :action (project-uri project-id "/analytics/labels")}
      {:tab-id :feedback
       :content [:span  "Feedback"]
       :action (project-uri project-id "/analytics/feedback")}]
     []
     active-tab
     "bottom attached project-menu-2"
     false]))

(defn project-submenu []
  (let [mobile? (util/mobile?)
        project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 2) first)
        {:keys [total]} @(subscribe [:project/article-counts])
        content (fn [title & [icon]]
                  [:span (when (and icon (not mobile?))
                           [:i.icon {:class icon}])
                   title])]
    [secondary-tabbed-menu
     [{:tab-id :add-articles
       :content (content "Sources" "list")
       :action (project-uri project-id "/add-articles")}
      {:tab-id :labels
       :content (content (if mobile? "Labels" "Label Definitions") "tags")
       :action (project-uri project-id "/labels/edit")}
      (when (and (> total 0) (not mobile?))
        {:tab-id :export-data
         :content (content "Export" "download")
         :action (project-uri project-id "/export")})
      (when (and @(subscribe [:member/admin? true])
                 (or (re-matches #".*@insilica.co" @(subscribe [:user/email]))
                     (beta-compensation-user? @(subscribe [:user/email]))))
        {:tab-id :compensation
         :content (content "Compensation")
         :action (project-uri project-id "/compensations")})
      {:tab-id :settings
       :content (content "Settings" "configure")
       :action (project-uri project-id "/settings")}]
     [#_[{:tab-id :support
          :content (content "Support" "dollar sign")
          :action (project-uri project-id "/support")}]]
     active-tab
     "bottom attached project-menu-2"
     mobile?]))

(defn project-page-menu []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) (drop 1) (take 2) vec)
        active-tab (if (in? [[:project :add-articles]
                             [:project :labels]
                             [:project :export-data]
                             [:project :settings]
                             [:project :support]
                             [:project :compensation]]
                            active-tab)
                     :manage active-tab)
        manage? (= active-tab :manage)
        {:keys [total]}
        @(subscribe [:project/article-counts])
        mobile? (util/mobile?)
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
          {:tab-id [:project :articles-data]
           :class "articles-data"
           :content [:span
                     (when-not mobile?
                       [:span [:i.table.icon] " "])
                     "Data"]
           :action (project-uri project-id "/data")
           :disabled (not ready?)
           :tooltip not-ready-msg}
          {:tab-id [:project :users]
           :class "users"
           :content [:span
                     (when-not mobile?
                       [:span [:i.users.icon] " "])
                     "Users"]
           :action (project-uri project-id "/users")
           :disabled (not ready?)
           :tooltip not-ready-msg}
          {:tab-id [:project :analytics]
           :class "analytics"
           :content [:div
                     (when-not mobile? [:span [:i.chart.bar.icon] " "])
                     "Analytics"]
           :action (project-uri project-id "/analytics/concordance")
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
         (if (or manage? (= active-tab [:project :analytics]))
           "attached project-menu"
           "bottom attached project-menu")
         mobile?]
        (when manage?
          ^{:key [:project-manage-menu]}
          [project-submenu])
        (when (= active-tab [:project :analytics])
          ^{:key [:project-analytics-menu]}
          [analytics-submenu]))))))

(defn ReadOnlyMessage [text & [message-closed-atom]]
  (when (and (not @(subscribe [:member/admin? true]))
             (not (and message-closed-atom @message-closed-atom)))
    [:div.ui.icon.message.read-only-message
     [:i.lock.icon]
     (when message-closed-atom
       [:i {:class "close icon"
            :on-click #(do (reset! message-closed-atom true))}])
     [:div.content
      [:div.header "Read-Only View"]
      [:p text]]]))
