(ns sysrev.views.panels.project.main
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.routes :as routes]
            [sysrev.util :refer [nbsp]]
            [sysrev.base :refer [active-route]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.project.common
             :refer [project-page-menu project-submenu]]
            [sysrev.views.project-list :as plist]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.user.projects :refer [MakePublic]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn ProjectContent [child]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project project-id]] {}
      (let [project-name @(subscribe [:project/name])
            public? @(subscribe [:project/public-access?])
            access-label (if public?
                           [:div.ui.label [:i.globe.icon] "Public"]
                           [:div.ui.label [:i.lock.icon] "Private"])]
        [:div
         [:div.ui.top.attached.middle.aligned.grid.segment.project-header.desktop
          [:div.row
           [:div.fourteen.wide.column
            [:h4.ui.header.title-header
             [:i.grey.list.alternate.outline.icon]
             [:div.content
              [:span.project-title project-name]]]]
           [:div.two.wide.right.aligned.column access-label]]]
         [:div.ui.top.attached.segment.project-header.mobile
          [:h4.ui.header.title-header
           [:i.grey.list.alternate.outline.icon]
           [:div.content
            [:span.project-title project-name]
            [:span.access-header access-label]]]]
         (project-page-menu)
         child]))))

(defn ProjectErrorNotice [message]
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:div.ui.large.icon.message
      [:i.warning.icon]
      [:div.content message]
      (when (and @(subscribe [:user/admin?])
                 project-id
                 (not @(subscribe [:project/not-found?])))
        [:button.ui.purple.button
         {:on-click #(dispatch [:action [:join-project project-id]])}
         "Join [admin]"])]
     (when (not @(subscribe [:self/logged-in?]))
       [LoginRegisterPanel])]))

(defn PrivateProjectNotViewable
  [project-id]
  (when-let [url-id @(subscribe [:active-project-url])]
    (with-loader [[:lookup-project-url url-id]] {}
      (let [project-owner @(subscribe [:project/owner project-id])
            project-owner-type (-> project-owner keys first)
            project-owner-id (-> project-owner vals first)
            self-id @(subscribe [:self/user-id])
            project-url @(subscribe [:project/uri project-id])]
        [:div "This private project is currently inaccessible"
         (when @(subscribe [:project/controlled-by? project-id self-id])
           (when (= :user-id project-owner-type)
             (dispatch [:user/set-on-subscribe-nav-to-url! project-url]))
           (when (= :group-id project-owner-type)
             (dispatch [:org/set-on-subscribe-nav-to-url! project-owner-id project-url]))
           [:div
            [:a {:href (if (= :user-id project-owner-type)
                         "/user/plans"
                         (str "/org/" project-owner-id "/plans"))}
             "Upgrade your plan"]
            " or "
            [MakePublic {:project-id project-id}]])]))))

(defn ProjectPanel [child]
  (when @(subscribe [:have? [:identity]])
    ;; redirect to standard project url if needed
    (when-let [redirect-id @(subscribe [:project-redirect-needed])]
      (let [[_ suburi] (re-matches #".*/p/[\d]+(.*)" @active-route)
            std-uri @(subscribe [:project/uri redirect-id suburi])]
        (dispatch [:nav-redirect std-uri])))
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
                       (not @(subscribe [:user/actual-admin?])))
                  [ProjectErrorNotice
                   [PrivateProjectNotViewable project-id]]

                  :else
                  [ProjectContent child])))))))

(defmethod panel-content [:project] []
  (fn [child] [ProjectPanel child]))

(defmethod panel-content [:project :project] []
  (fn [child]
    (let [project-id @(subscribe [:active-project-id])]
      [:div
       (when (and project-id
                  @(subscribe [:project/subscription-lapsed? project-id])
                  @(subscribe [:user/actual-admin?]))
         [:div.ui.small.warning.message "Subscription Lapsed (dev override)"])
       child])))
