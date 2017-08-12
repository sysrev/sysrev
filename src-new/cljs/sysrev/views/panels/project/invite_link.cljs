(ns sysrev.views.panels.project.invite-link
  (:require
   [re-frame.core :refer [subscribe dispatch]]
   [sysrev.views.components :refer [clipboard-button]]
   [sysrev.views.base :refer [panel-content logged-out-content]]))

(defmethod panel-content [:project :project :invite-link] []
  (fn [child]
    [:div
     (when-let [invite-url @(subscribe [:project/invite-url])]
       [:div.ui.two.column.stackable.grid
        [:div.row
         [:div.column
          [:div.ui.segment
           [:h5 "Send this link to invite another person to join the project:"]
           [:div.ui.fluid.action.input
            [:input#invite-url.ui.input {:readOnly true
                                         :value invite-url}]
            [clipboard-button "#invite-url" "Copy URL"]]]]]])
     child]))
