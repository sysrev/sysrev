(ns sysrev.views.panels.project.settings
  (:require
   [re-frame.core :refer [subscribe dispatch]]
   [sysrev.views.base :refer [panel-content logged-out-content]]))


(defmethod panel-content [:project :project :settings] []
  (fn [child]
    [:div]))
