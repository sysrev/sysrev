(ns sysrev.views.panels.user-settings
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.util :refer [full-size?]]))

(defmethod panel-content [:user-settings] []
  (fn [child]
    [:div.user-settings
     [:div.ui.segment
      [:h4.ui.dividing.header "User Settings"]]]))
