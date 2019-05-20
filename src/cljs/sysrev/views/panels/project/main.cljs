(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.routes :as routes]
            [sysrev.util :refer [nbsp]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.project.common
             :refer [project-page-menu project-submenu]]
            [sysrev.views.project-list :as plist]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.user.projects :refer [MakePublic]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def initial-state {:create-project nil})
(defonce state (r/cursor app-db [:state :panels [:project]]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn ProjectContent [child]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project project-id]] {}
      (let [project-name @(subscribe [:project/name])
            public? @(subscribe [:project/public-access?])
            access-label
            (if public?
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
  (let [project-owner @(subscribe [:project/owner project-id])
        project-owner-type (-> project-owner keys first)
        project-owner-id (-> project-owner vals first)
        self-user-id @(subscribe [:self/user-id])]
    [:div "This private project is currently inaccessible"
     (when (or
            ;; user is an admin of this project
            (and (= :user-id project-owner-type)
                 @(subscribe [:member/admin? self-user-id project-id]))
            ;; user is an admin or owner of the org this project belongs to
            (and (= :group-id project-owner-type)
                 (some #{"admin" "owner"} @(subscribe [:member/org-permissions project-owner-id]))))
       (when (= :user-id project-owner-type)
         (dispatch [:plans/set-on-subscribe-nav-to-url! (str "/p/" project-id)]))
       ;; needs to be set for the plan org
       (when (= :group-id project-owner-type)
         (dispatch [:org/set-on-subscribe-nav-to-url! (str "/p/" project-id)])
         (dispatch [:set-current-org! project-owner-id])
         (dispatch [:fetch [:org-current-plan project-owner-id]]))
       [:div
        [:a {:href (if (= :user-id project-owner-type)
                     "/user/plans"
                     "/org/plans")}
         "Upgrade your plan"]
        " or "
        [MakePublic project-id]])]))

(defn ProjectPanel [child]
  (ensure-state)
  (when @(subscribe [:have? [:identity]])
    ;; need project info for :project/private-not-viewable?
    (with-loader [[:project @(subscribe [:active-project-id])]] {}
      (let [project-id @(subscribe [:active-project-id])]
        (cond (or (nil? project-id)
                  @(subscribe [:project/not-found? project-id]))
              [:div
               [ProjectErrorNotice "Project not found"]
               [:div {:style {:margin-top "16px"}}
                [plist/UserProjectListFull]]]

              @(subscribe [:project/unauthorized? project-id])
              [ProjectErrorNotice "Not authorized to view project"]

              @(subscribe [:project/error? project-id])
              [ProjectErrorNotice "Unable to load project"]

              @(subscribe [:project/private-not-viewable? project-id])
              [ProjectErrorNotice
               [PrivateProjectNotViewable project-id]]

              :else
              [ProjectContent child])))))

(defmethod panel-content [:project] []
  (fn [child] [ProjectPanel child]))

(defmethod panel-content [:project :project] []
  (fn [child] [:div child]))
