(ns sysrev.views.panels.project.edit-labels
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :refer [true-false-nil-tag]]))

(defmethod panel-content [:project :project :labels :edit] []
  (fn [child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))]
      [:div
       [:div.ui.top.attached.header.segment
        [:h4 "Edit Definitions"]]
       [:div.ui.bottom.attached.segment
        [:div.ui.one.wide.grid
         [:div.row
          [:div.column
           [:span "Interface here"]]]]]])))
