(ns sysrev.views.panels.project.main
  (:require [goog.uri.utils :as uri-utils]
            [re-frame.core :refer [subscribe dispatch reg-event-db reg-sub]]
            [sysrev.base :refer [active-route]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri user-uri group-uri]]
            [sysrev.util :as util :refer [format]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components.clone-project :refer [CloneProject]]
            [sysrev.views.panels.project.common :refer [project-page-menu]]
            [sysrev.views.project-list :as plist]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.user.projects :refer [MakePublic]]
            [sysrev.views.project :refer [ProjectName]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn ProjectTitle [project-id]
  (let [project-owner @(subscribe [:project/owner project-id])
        project-name @(subscribe [:project/name project-id])
        parent-project @(subscribe [:project/parent-project project-id])
        public? @(subscribe [:project/public-access?])]
    [:span.ui.header.title-header
     (if public?
       [:i.grey.list.alternate.outline.icon]
       [:i.grey.lock.icon])
     [:div.content.project-title
      (when project-owner
        [:span
         [:a {:href (or (some-> (:user-id project-owner) user-uri)
                        (some-> (:group-id project-owner) group-uri))}
          (:name project-owner)]
         [:span.bold {:style {:font-size "1.1em" :margin "0 0.325em"}} "/"]] )
      [:a {:href (project-uri nil "")} project-name]]
     (when parent-project
       [:div {:style {:margin-top "0.5rem"
                      :font-size "0.9rem"}}
        [:span.ui.grey.text {:font-""} "cloned from "]
        [:a {:href (str "/p/" (:project-id parent-project))}
         [ProjectName (:project-name parent-project) (:owner-name parent-project)]]])]))

(reg-event-db ::set-message-dismissed-for-project
              (fn [db [_ project-id]]
                (assoc db ::message-dismissed-for-project project-id)))

(reg-sub ::message-dismissed-for-project #(::message-dismissed-for-project %))

(defn ProjectContent [child]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project project-id]] {}
      (let [public? @(subscribe [:project/public-access?])
            logged-in? @(subscribe [:self/logged-in?])
            access-button (fn [content]
                            [:button.ui.tiny.button.project-access
                             {:on-click (when @(subscribe [:user/project-admin?])
                                          #(nav/nav (project-uri nil "/settings")))}
                             content])
            access-label (if public?
                           [access-button [:span [:i.globe.icon] "Public"]]
                           [access-button [:span [:i.lock.icon] "Private"]])
            message-dismissed-for-project @(subscribe [::message-dismissed-for-project])]
        [:div
         [:div.ui.top.attached.middle.aligned.grid.segment.project-header.desktop
          [:div.row
           [:div.eleven.wide.column
            [ProjectTitle project-id]]
           [:div.right.aligned.column.five.wide
            (when-not (util/mobile?) [CloneProject])
            access-label]]]
         [:div.ui.top.attached.segment.project-header.mobile
          [:div.row
           [:div.thirteen.wid.column]
           [ProjectTitle project-id]]
          [:div.row
           [:div.content
            (when (util/mobile?) [CloneProject])
            [:span.access-header access-label]]]]
         (project-page-menu)
         (when (and (not logged-in?) (not= message-dismissed-for-project project-id))
           [:div.ui.positive.message {:style {:text-align "center"}}
            [:i.close.icon {:on-click (fn [_] (dispatch [::set-message-dismissed-for-project project-id]))}]
            [:div.header {:style {:margin "auto"}} "Welcome to Sysrev!"]
            [:p "Join today to create projects like this one."]
            [:a.ui.primary.button {:href "/register"} "Try for Free"]])
         child]))))

(defn ProjectErrorNotice [message]
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:div.ui.large.icon.message
      [:i.warning.icon]
      [:div.content message]
      (when (and @(subscribe [:user/dev?])
                 project-id
                 (not @(subscribe [:project/not-found?])))
        [:button.ui.purple.button
         {:on-click #(dispatch [:action [:join-project project-id]])}
         "Join [admin]"])]
     (when (not @(subscribe [:self/logged-in?]))
       [LoginRegisterPanel])]))

(defn PrivateProjectNotViewable [project-id]
  (when-let [url-id @(subscribe [:active-project-url])]
    (with-loader [[:lookup-project-url url-id]] {}
      (let [project-owner @(subscribe [:project/owner project-id])
            self-id @(subscribe [:self/user-id])
            project-url @(subscribe [:project/uri project-id])]
        [:div "This private project is currently inaccessible"
         (when @(subscribe [:project/controlled-by? project-id self-id])
           [:div
            [:a {:href (nav/make-url (if (:user-id project-owner)
                                       "/user/plans"
                                       (format "/org/%s/plans" (:group-id project-owner)))
                                     {:on_subscribe_uri project-url})}
             "Upgrade your plan"] " or "
            [MakePublic {:project-id project-id}]])]))))

(defn ProjectPanel [child]
  (when @(subscribe [:have? [:identity]])
    ;; redirect to standard project url if needed
    (when-let [redirect-id @(subscribe [:project-redirect-needed])]
      (let [[_ suburi] (re-matches #".*/p/[\d]+(.*)" @active-route)
            std-uri @(subscribe [:project/uri redirect-id suburi])]
        (dispatch [:nav std-uri :redirect true])))
    (when-let [url-id @(subscribe [:active-project-url])]
      ;; make sure we've queried for translation from url -> project-id
      (with-loader [[:lookup-project-url url-id]] {}
        ;; continue if we have a project id from the url
        (when-let [project-id @(subscribe [:active-project-id])]
          ;; wait for project data to load
          (with-loader [[:project project-id]] {}
            (cond @(subscribe [:project/not-found? project-id])
                  [:div
                   [ProjectErrorNotice "Project not found"]
                   [:div {:style {:margin-top "16px"}}
                    [plist/UserProjectListFull]]]

                  @(subscribe [:project/unauthorized? project-id])
                  [ProjectErrorNotice "Not authorized to view project"]

                  @(subscribe [:project/error project-id])
                  [ProjectErrorNotice "Unable to load project"]

                  (and @(subscribe [:project/subscription-lapsed? project-id])
                       ;; Don't block real (non-test) dev users from seeing projects
                       (not @(subscribe [:user/dev?])))
                  [ProjectErrorNotice
                   [PrivateProjectNotViewable project-id]]

                  :else
                  [ProjectContent child])))))))

(defmethod panel-content [:project] []
  (fn [child]
    (when (uri-utils/getParamValue @active-route "cloning")
      (dispatch [:sysrev.views.components.clone-project/modal-open? true]))
    [ProjectPanel child]))

(defmethod panel-content [:project :project] []
  (fn [child]
    (let [project-id @(subscribe [:active-project-id])]
      [:div
       (when (and project-id
                  @(subscribe [:project/subscription-lapsed? project-id])
                  @(subscribe [:user/dev?]))
         [:div.ui.small.warning.message "Subscription Lapsed (dev override)"])
       child])))
