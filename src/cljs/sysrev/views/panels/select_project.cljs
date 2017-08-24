(ns sysrev.views.panels.select-project
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]
   [sysrev.views.panels.project.main :refer [project-header]]
   [sysrev.util :refer [go-back]]))

(defmethod panel-content [:select-project] []
  (fn [child]
    (let [active-id @(subscribe [:active-project-id])]
      [:div
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
            (fn [{:keys [project-id name]}]
              (let [active? (= project-id active-id)]
                ^{:key project-id}
                [:div.column
                 [:div.ui.fluid.left.labeled.button
                  {:style {:text-align "justify"}}
                  [:div.ui.fluid.basic.label (str name)]
                  [:a.ui.button {:style {:padding "1.5em"}
                                 :on-click #(dispatch [:action [:select-project project-id]])}
                   "Go"]]])))))]]])))
