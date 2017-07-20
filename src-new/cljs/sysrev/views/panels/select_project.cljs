(ns sysrev.views.panels.select-project
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]
   [sysrev.util :refer [go-back]]))

(defmethod panel-content [:select-project] []
  (fn [child]
    (let [active-id @(subscribe [:active-project-id])]
      [:div.ui.segment
       [:h3.ui.dividing.header
        {:style {:padding-bottom "0.5rem"}}
        [:div.ui.middle.aligned.two.column.grid
         [:div.column
          [:span {:style {:padding-left "0.2rem"}}
           "Select project"]]
         [:div.right.aligned.column
          [:a.ui.small.button {:style {:margin-right "0"}
                               :on-click go-back}
           "Back"]]]]
       [:div.ui.two.column.stackable.grid.projects
        (doall
         (->>
          @(subscribe [:self/projects])
          (map
           (fn [{:keys [project-id name]}]
             (let [active? (= project-id active-id)]
               ^{:key project-id}
               [:div.column
                [:a.ui.fluid.left.labeled.button
                 {:style {:text-align "justify"}
                  :on-click #(dispatch [:action [:select-project project-id]])}
                 [:div.ui.fluid.basic.label (str name)]
                 [:div.ui.button {:style {:padding "1.5em"}}
                  "Go"]]])))))]])))
