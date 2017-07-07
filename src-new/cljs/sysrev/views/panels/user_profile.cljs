(ns sysrev.views.panels.user-profile
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.project :refer [project-wrapper]]
   [sysrev.views.components]))

(defn user-profile-panel []
  [:div])

(defmethod panel-content :user-profile []
  [project-wrapper [user-profile-panel]])
