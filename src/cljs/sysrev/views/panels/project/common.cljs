(ns sysrev.views.panels.project.common
  (:require [re-frame.core :refer [subscribe]]
            [clojure.string :as str]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.core :as ui :refer [CursorMessage]]
            [sysrev.util :as util :refer [in?]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.views.semantic :refer
             [Modal ModalContent ModalHeader ModalDescription TextArea]]
            [reagent.core :as r]))

(def beta-compensation-users #{"eliza.grames@uconn.edu"})

(defn beta-compensation-user? [email]
  (contains? beta-compensation-users email))

(defn- analytics-submenu []
  (let [project-id @(subscribe [:active-project-id])
        active-tab (->> @(subscribe [:active-panel]) last)]
    [ui/SecondaryTabbedMenu
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
    [ui/SecondaryTabbedMenu
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
                 (or (re-matches #".*@insilica.co" @(subscribe [:self/email]))
                     (beta-compensation-user? @(subscribe [:self/email]))))
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
        [ui/PrimaryTabbedMenu
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
           :action (project-uri project-id "/users")}
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

(defn format-issue-email [{:keys [message projectId user]}]
  (str "Project issue form from: " user "
    <br>
    For project: " projectId
   "<br>
    Message: " message))


(def-action :project/report-issue
  :uri      (fn [_ _] (str "/api/send-contact-email"))
  :content  (fn [content] {:content (:content content) :subject "Project Issue Submission"})
  :process  (fn [{:keys [db]} [self-id _] _]
              (reset! (:on-fail self-id) nil)
              (reset! (:on-success self-id) "Message Sent!")
              db)
  :on-error (fn [{:keys [db error]} self-id _]
              (reset! (:on-failed self-id) "Message Send Failure.")))

(defn ProjectIssueModal []
  (let [modal-open (r/atom false)
        email-content (r/atom "")
        email-success (r/atom nil)
        email-failed (r/atom nil)]
    (fn []
      (let [project-id @(subscribe [:active-project-id])
            self-id @(subscribe [:self/user-id])]
        [:div {:style {:display "inline-block" :margin-left "0.25rem"}}
         [:button.ui.tiny.button.red {:on-click #(reset! modal-open true)} "Report Issue"]
         [Modal {:trigger
                 (r/as-element
                  [:div.ui {:id :change-avatar
                            :data-tooltip "Report and Issue"
                            :data-position "bottom center"}])
                 :open @modal-open
                 :on-open #(reset! modal-open true)
                 :on-close #(reset! modal-open false)
                 :size "tiny"}
          [ModalHeader "Report an Issue"]
          [ModalContent
           [ModalDescription
            [TextArea {
                       :style {:min-height "15em" :width "100%"}
                       :placeholder "Your Message"
                       :on-change (util/on-event-value #(reset! email-content %))}]
            [:button.ui.small.positive.button
             {:on-click #(if (str/blank? (str @email-content))
                          (reset! email-failed  "Message Can't Be Blank.")
                          (run-action :project/report-issue {:content (format-issue-email  {:message @email-content
                                                                                            :projectId project-id
                                                                                            :user self-id})
                                                             :on-success email-success
                                                             :on-fail email-failed}))}
             "Submit"]
            (when (some seq [@email-success @email-failed])
             [:div {:style {:padding-top "0" :margin-top "1em"}}
              [CursorMessage email-success {:positive true}]
              [CursorMessage email-failed {:negative true}]])]]]]))))
