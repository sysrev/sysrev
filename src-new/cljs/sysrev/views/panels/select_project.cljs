(ns sysrev.views.panels.select-project
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]))

(defmethod panel-content [:select-project] []
  (fn [child]
    [:div.ui.segments
     [:div.ui.top.attached.header.segment
      [:h3 "Your projects"]]
     [:div.ui.bottom.attached.segment
      nil]]))
