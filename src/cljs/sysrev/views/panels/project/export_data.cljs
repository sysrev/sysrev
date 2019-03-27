(ns sysrev.views.panels.project.export-data
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.project.define-labels :refer [label-settings-config]]
            [sysrev.views.components :refer
             [with-ui-help-tooltip ui-help-icon]]
            [sysrev.util :as util :refer [today-string nbsp]]))

(def-action :project/generate-export
  :uri (fn [project-id export-type _]
         (str "/api/generate-project-export/" project-id "/" (name export-type)))
  :content (fn [_ _ options] (merge options {}))
  :process (fn [{:keys [db]} [project-id export-type options] {:keys [entry] :as result}]
             {:db (assoc-in db [:data :project-exports [project-id export-type options]] entry)})
  :on-error (fn [{:keys [db error]} [project-id export-type options] _]
              {:db (assoc-in db [:data :project-exports [project-id export-type options]]
                             {:error error})}))

(reg-sub
 :project/export-file
 (fn [db [_ project-id export-type options]]
   (get-in db [:data :project-exports [project-id export-type options]])))

(defn ProjectExportNavigateForm [export-type]
  [:a.ui.fluid.right.labeled.icon.primary.button
   {:on-click (util/wrap-user-event
               #(dispatch [:articles/load-export-settings export-type true]))}
   [:i.arrow.circle.right.icon] "Configure Export"])

(defmethod panel-content [:project :project :export-data] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      [:div.project-content
       [:div.ui.two.column.stackable.grid.export-data
        [:div.column
         [:div.ui.segment
          [:h4.ui.dividing.header "Group Answers"]
          [:p "This provides a CSV file containing the label answers from all project members for each article."]
          [:p (str "Each row contains answers for one article. Values are combined from all user answers; enabling "
                   (pr-str (-> label-settings-config :consensus :display))
                   " for a label can ensure that user answers are identical.") ]
          [:p "By default, includes all labeled articles except those in Conflict status; this can be customized from the Articles page."]
          [ProjectExportNavigateForm :group-answers]]
         [:div.ui.segment
          [:h4.ui.dividing.header "User Answers"]
          [:p "This provides a CSV file containing the exact answers saved by each user for each article."]
          [:p "Each row contains answers that one user saved for one article."]
          [:p "By default, includes all labeled articles; this can be customized from the Articles page."]
          [ProjectExportNavigateForm :user-answers]]
         [:div.ui.segment
          [:h4.ui.dividing.header "Annotations"]
          [:p "This provides a CSV file containing the annotations users have attached to articles."]
          [:p "Each row contains the fields for one annotation."]
          [:p "By default, includes all annotated articles; this can be customized from the Articles page."]
          [ProjectExportNavigateForm :annotations-csv]]]
        [:div.column
         [:div.ui.segment
          [:h4.ui.dividing.header "Articles (EndNote XML)"]
          [:p "This provides a set of articles in EndNote's XML format, for import to EndNote or other compatible software."]
          [:p "By default, includes all articles; this can be customized from the Articles page."]
          [ProjectExportNavigateForm :endnote-xml]]
         [:div.ui.segment
          [:h4.ui.dividing.header "Articles (CSV)"]
          [:p "This provides a set of articles in CSV format, with the basic fields associated with the article from its import."]
          [:p "This file also includes any label prediction scores for each article."]
          [:p "By default, includes all articles; this can be customized from the Articles page."]
          [ProjectExportNavigateForm :articles-csv]]]]
       child])))
