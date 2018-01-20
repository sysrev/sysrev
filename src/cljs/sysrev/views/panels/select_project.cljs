(ns sysrev.views.panels.select-project
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [reagent.core :as r]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]
   [sysrev.views.panels.create-project :refer [CreateProject]]
   [sysrev.views.panels.project.common :refer [project-header]]
   [sysrev.util :refer [go-back]]))

(def panel [:select-project])

(def initial-state {:create-project nil})
(def state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn SelectProject []
  (ensure-state)
  (let [active-id @(subscribe [:active-project-id])]
    [:div
     [CreateProject state]
     [project-header
      "Select project"
      [:a.ui.button {:on-click go-back}
       "Back"]]
     [:div.ui.bottom.attached.segment
      [:div.ui.two.column.stackable.grid.projects
       (doall
        (->>
         @(subscribe [:self/projects])
         (map
          (fn [{:keys [project-id name member?]}]
            (let [active? (= project-id active-id)]
              ^{:key project-id}
              [:div.column
               [:div.ui.fluid.left.labeled.button
                {:style {:text-align "justify"}}
                [:div.ui.fluid.basic.label (str name)]
                (if member?
                  [:a.ui.button {:style {:padding "1.5em"}
                                 :on-click #(dispatch [:action [:select-project project-id]])}
                   "Go"]
                  [:a.ui.blue.button {:style {:padding "1.5em"}
                                      :on-click #(dispatch [:action [:join-project project-id]])}
                   "Join"])]])))))]]]))

(defmethod panel-content panel []
  (fn [child]
    [SelectProject]))
