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
      [:div.project-content
       [:div.ui.two.column.stackable.grid
        [:div.row
         [:div.column
          [:div.ui.segment
           [:h4.ui.dividing.header
            "Export User Answers (CSV)"]
           [:h5 "Download CSV file of all user answers and notes"]
           [:div.ui.fluid.left.labeled.button
            [:div.ui.fluid.label "User Answers"]
            [:a.ui.fluid.primary.button
             {:href "/api/export-answers-csv"
              :target "_blank"
              :download (str "sr_answers_" project-id "_" (today-string) ".csv")}
             "Download"]]]]
         [:div.column
          [:div.ui.segment
           [:h4.ui.dividing.header
            "Export Project Data (JSON)"]
           [:h5 "Download raw dump of project data in internal custom format"]
           [:div.ui.fluid.left.labeled.button
            [:div.ui.fluid.label "Project Data"]
            [:a.ui.fluid.primary.button
             {:href "/api/export-project"
              :target "_blank"
              :download (str "sr_project_" project-id "_" (today-string) ".json")}
             "Download"]]]]]]
       child])))
