(ns sysrev.views.panels.project.export-data
  (:require
   [re-frame.core :refer [subscribe dispatch]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer
    [with-ui-help-tooltip ui-help-icon]]
   [sysrev.util :refer [today-string]]))

(defmethod panel-content [:project :project :export-data] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      [:div
       [:div.ui.two.column.stackable.grid
        [:div.row
         [:div.column
          [:div.ui.grey.segment
           [:h5.ui.dividing.header
            (doall
             (with-ui-help-tooltip
               [:span
                "Export project data "
                [ui-help-icon]]
               :help-content
               ["Download JSON-format export of articles and labels"]))]
           [:div.ui.fluid.left.labeled.button
            [:div.ui.fluid.label "v1.0.1 JSON"]
            [:a.ui.fluid.primary.button
             {:href "/api/export-project"
              :target "_blank"
              :download (str "sr_project_" project-id "_" (today-string) ".json")}
             "Download"]]]]]]
       child])))
