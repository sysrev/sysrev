(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.routes :as routes]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.common
             :refer [project-page-menu project-submenu]]
            [sysrev.views.panels.select-project :refer [SelectProject]]
            [sysrev.util :refer [nbsp]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def initial-state {:create-project nil})
(defonce state (r/cursor app-db [:state :panels [:project]]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defmethod panel-content [:project] []
  (fn [child]
    (ensure-state)
    (if (empty? @(subscribe [:self/projects]))
      [SelectProject]
      (with-loader [[:project]] {}
        (let [project-name @(subscribe [:project/name])
              admin? @(subscribe [:user/admin?])
              projects @(subscribe [:self/projects true])]
          [:div
           [:div.ui.top.attached.segment.project-header
            [:div.ui.middle.aligned.grid
             [:div.row
              [:div.sixteen.wide.column.project-title
               [:span.project-title [:i.grey.book.icon] nbsp project-name]]]]]
           (project-page-menu)
           child])))))

(defmethod panel-content [:project :project] []
  (fn [child]
    [:div
     child]))
