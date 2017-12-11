(ns sysrev.views.panels.create-project
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.util :refer [full-size? mobile?]]
   [sysrev.shared.util :refer [in?]]))

(defmethod panel-content [:create-project] []
  (fn [child]
    [:div.create-project
     child]))
