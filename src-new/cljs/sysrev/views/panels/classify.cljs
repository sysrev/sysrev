(ns sysrev.views.panels.classify
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.project :refer [project-wrapper]]
   [sysrev.views.components]))

(defn classify-panel []
  [:div])

(defmethod panel-content :classify []
  [project-wrapper [classify-panel]])
