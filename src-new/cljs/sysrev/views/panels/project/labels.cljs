(ns sysrev.views.panels.project.labels
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]))

(defmethod panel-content [:project :project :labels] []
  (fn [child]
    [:div]))
