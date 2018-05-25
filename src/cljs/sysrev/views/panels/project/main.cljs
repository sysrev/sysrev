(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.routes :as routes]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.project.common
             :refer [project-page-menu project-submenu]]
            [sysrev.views.panels.select-project :refer [SelectProject]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.util :refer [nbsp]])
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
              [:div.ui.label [:i.fitted.globe.icon] "Public"]
              [:div.ui.label [:i.fitted.lock.icon] "Private"])]
        [:div
         [:div.ui.top.attached.grid.segment.project-header.desktop
          [:div.row
           [:div.fourteen.wide.column
            [:h4.ui.header.title-header
             [:i.grey.book.icon]
             [:div.content
              [:span.project-title project-name]]]]
           [:div.two.wide.right.aligned.column access-label]]]
         [:div.ui.top.attached.segment.project-header.mobile
          [:h4.ui.header.title-header
           [:i.grey.book.icon]
           [:div.content
            [:span.project-title project-name]
            [:span.support [:div.ui.label {:on-click #(dispatch [:navigate [:project :project :support]
                                                                 {:project-id project-id}])
                                           :style {:cursor "pointer"}} "Support"]]
            [:span.access-header access-label]]]]
         (project-page-menu)
         child]))))

(defn ProjectErrorNotice [message]
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:div.ui.large.icon.message
      [:i.warning.icon]
      [:div.content message]
      (when @(subscribe [:user/admin?])
        [:button.ui.purple.button
         {:on-click #(dispatch [:action [:join-project project-id]])}
         "Join [admin]"])]
     (when (not @(subscribe [:self/logged-in?]))
       [LoginRegisterPanel])]))

(defn ProjectPanel [child]
  (ensure-state)
  (when @(subscribe [:have? [:identity]])
    (let [project-id @(subscribe [:active-project-id])]
      (cond (nil? project-id)
            [:div
             [ProjectErrorNotice
              "Project not found"]
             [:div {:style {:margin-top "16px"}}
              [SelectProject]]]

            @(subscribe [:project/unauthorized? project-id])
            [ProjectErrorNotice
             "Not authorized to view project"]

            @(subscribe [:project/error? project-id])
            [ProjectErrorNotice
             "Unable to load project"]

            :else
            [ProjectContent child]))))

(defmethod panel-content [:project] []
  (fn [child] [ProjectPanel child]))

(defmethod panel-content [:project :project] []
  (fn [child] [:div child]))
