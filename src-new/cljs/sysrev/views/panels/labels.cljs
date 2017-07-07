(ns sysrev.views.panels.labels
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.project :refer [project-wrapper]]
   [sysrev.views.components]))

(defn project-labels-panel []
  [:div])

(defmethod panel-content :labels []
  [project-wrapper [project-labels-panel]])
