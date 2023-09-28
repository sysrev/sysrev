(ns sysrev.views.panels.enterprise
  (:require [re-frame.core :refer [dispatch subscribe]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.views.semantic :refer [Header Segment]]))

(declare panel)
(setup-panel-state panel [:enterprise])

(defn Enterprise []
  (let [logged-in? (subscribe [:self/logged-in?])
        user-id (subscribe [:self/user-id])]
    (when (and @logged-in? (nil? (:nickname @(subscribe [:user/current-plan]))))
      (dispatch [:data/load [:user/current-plan @user-id]])
      (dispatch [:fetch [:user/orgs @user-id]]))
    (fn []
      [Segment
       [Header {:as "h2" :align "center"} "Enterprise"]
       [:p {:style {:text-align "center"}}
        "Placeholder for enterprise contract"]])))

(def-panel :uri "/enterprise" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content [Enterprise])
