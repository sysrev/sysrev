(ns sysrev.views.panels.project.main
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.routes :as routes]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.common
             :refer [project-header project-page-menu project-submenu]]
            [sysrev.views.panels.select-project :refer [SelectProject]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def initial-state {:create-project nil})
(defonce state (r/cursor app-db [:state :panels [:project]]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defmethod panel-content [:project] []
  (fn [child]
    (ensure-state)
    (if (empty? (->> @(subscribe [:self/projects])
                     (filter :member?)))
      [SelectProject]
      (with-loader [[:project]] {}
        (let [project-name @(subscribe [:project/name])
              admin? @(subscribe [:user/admin?])
              projects @(subscribe [:self/projects])]
          [:div
           [project-header
            project-name
            [:div
             [:a.ui.tiny.button
              {:on-click #(dispatch [:navigate [:select-project]])
               :class (if (or admin? (< 1 (count projects)))
                        "" "disabled")}
              "Change"]]]
           [:div.ui.bottom.attached.segment.project-segment
            [project-page-menu]
            [:div child]]])))))

(defmethod panel-content [:project :project] []
  (fn [child]
    [:div
     [project-submenu]
     child]))
