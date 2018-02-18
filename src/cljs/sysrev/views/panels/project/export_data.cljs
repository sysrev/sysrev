(ns sysrev.views.panels.project.export-data
  (:require
   [re-frame.core :refer [subscribe dispatch]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer
    [with-ui-help-tooltip ui-help-icon]]
   [sysrev.util :refer [today-string nbsp]]))

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
           [:div
            (let [filename (str "sr_answers_" project-id "_" (today-string) ".csv")]
              [:a.medium-weight
               {:style {:font-size "16px"}
                :href (str "/api/export-answers-csv/" project-id "/" filename)
                :target "_blank"
                :download filename}
               filename])
            [:span nbsp nbsp "(right-click to download)"]]]]
         [:div.column
          [:div.ui.segment
           [:h4.ui.dividing.header
            "Export Project Data (JSON)"]
           [:h5 "Download raw dump of project data in internal custom format"]
           [:div
            (let [filename
                  (str "sr_project_" project-id "_" (today-string) ".json")]
              [:a.medium-weight
               {:style {:font-size "16px"}
                :href (str "/api/export-project/" project-id "/" filename)
                :target "_blank"
                :download filename}
               filename])
            [:span nbsp nbsp "(right-click to download)"]]]]]]
       child])))
