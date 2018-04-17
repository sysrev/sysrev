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
      (let [project-name @(subscribe [:project/name])]
        [:div
         [:div.ui.top.attached.segment.project-header
          [:div.ui.middle.aligned.grid
           [:div.row
            [:div.sixteen.wide.column.project-title
             [:span.project-title [:i.grey.book.icon] nbsp project-name]]]]]
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
