(ns sysrev.views.panels.project.export-data
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.views.panels.project.define-labels :refer [label-settings-config]]
            [sysrev.views.semantic :refer [Dropdown]]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :export-data]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-action :project/generate-export
  :uri (fn [project-id export-type _]
         (str "/api/generate-project-export/" project-id "/" (name export-type)))
  :content (fn [_ _ options]
             (merge options {}))
  :process (fn [{:keys [db]} [project-id export-type options] {:keys [entry]}]
             {:db (assoc-in db [:data :project-exports [project-id export-type options]] entry)})
  :on-error (fn [{:keys [db error]} [project-id export-type options] _]
              {:db (assoc-in db [:data :project-exports [project-id export-type options]]
                             {:error error})})
  :timeout (* 10 60 1000))

(reg-sub :project/export-file
         (fn [db [_ project-id export-type options]]
           (get-in db [:data :project-exports [project-id export-type options]])))

(defn ProjectExportNavigateForm [export-type]
  [:a.ui.fluid.right.labeled.icon.primary.button
   {:on-click (util/wrap-user-event
               #(dispatch [:articles/load-export-settings export-type true]))}
   [:i.arrow.circle.right.icon] "Configure Export"])

(defn ExportJSON []
  (let [project-id @(subscribe [:active-project-id])
        export-type :json
        options {}
        action [:project/generate-export project-id export-type options]
        running? (action/running? action)
        entry @(subscribe [:project/export-file project-id export-type options])
        {:keys [filename url error]} entry
        file? (and entry (not error))]
    [:div.ui.segment
     [:h4.ui.dividing.header "Project Data (json)"]
     [:p "This file contains the project in a hierarchical data format. This data is in the JSON format."]
     [:p "This includes all data for the project and can not be customized from the Articles page. This format is useful for external data analysis."]
     [:div.eight.wide.field
      [:button.ui.tiny.fluid.primary.labeled.icon.button
       {:on-click (when-not running? (util/wrap-user-event #(dispatch [:action action])))
        :class (css [running? "loading"])}
       [:i.hdd.icon] "Generate"]]
     (when-not error
       [:div.field>div.ui.center.aligned.segment.file-download
        {:style {:margin-top "1rem"}}
        (cond running?  [:span "Generating file..."]
              file?     [:a {:href url :target "_blank" :download filename}
                         [:i.outline.file.icon] " " filename]
              :else     [:span "<Generate file to download>"])])
     (when error
       [:div.field>div.ui.negative.message
        {:style {:margin-top "1rem"}}
        (or (-> error :message)
            "Sorry, an error occurred while generating the file.")])]))

(reg-sub ::group-label-values
         :<- [:project/labels-raw]
         (fn [labels-raw]
           (vec (for [{:keys [short-label label-id]}
                      (->> (vals labels-raw)
                           (filter #(= "group" (:value-type %))))]
                  {:text short-label :value (str label-id)}))))

(reg-sub ::group-label-option
         :<- [::get :group-label-option] :<- [::group-label-values]
         (fn [[option values]]
           (or option (:value (first values)))))

(defn ExportGroupLabelCSV []
  (let [project-id @(subscribe [:active-project-id])
        export-type :group-label-csv
        group-label-values @(subscribe [::group-label-values])
        value @(subscribe [::group-label-option])
        options {:label-id (util/to-uuid value)}
        action [:project/generate-export project-id export-type options]
        running? (action/running? action)
        entry @(subscribe [:project/export-file project-id export-type options])
        {:keys [filename url error]} entry
        file? (and entry (not error))]
    ;; test that there are actually group labels in this project
    (when (seq group-label-values)
      [:div.ui.segment
       [:h4.ui.dividing.header "Group Label CSV"]
       [:p "This file contains the data for a group label in CSV format."]
       [:p "Select the Group Label you would like to export"]
       [:div {:style {:margin-bottom "1rem"}}
        [Dropdown {:style {:min-width "12rem" :font-size "0.9em"}
                   :options group-label-values
                   :selection true
                   :value value
                   :on-change (fn [_ ^js data]
                                (dispatch [::set :group-label-option (.-value data)]))}]]
       [:div.eight.wide.field
        [:button.ui.tiny.fluid.primary.labeled.icon.button
         {:on-click (when-not running?
                      (util/wrap-user-event #(dispatch [:action action])))
          :class (css [running? "loading"])}
         [:i.hdd.icon] "Generate"]]
       (when-not error
         [:div.field>div.ui.center.aligned.segment.file-download
          {:style {:margin-top "1rem"}}
          (cond running?  [:span "Generating file..."]
                file?     [:a {:href url :target "_blank" :download filename}
                           [:i.outline.file.icon] " " filename]
                :else     [:span "<Generate file to download>"])])
       (when error
         [:div.field>div.ui.negative.message
          {:style {:margin-top "1rem"}}
          (or (-> error :message)
              "Sorry, an error occurred while generating the file.")])])))

(defn ExportUploadedArticlePDFs []
  (let [project-id @(subscribe [:active-project-id])
        export-type :uploaded-article-pdfs-zip
        options {}
        action [:project/generate-export project-id export-type options]
        running? (action/running? action)
        entry @(subscribe [:project/export-file project-id export-type options])
        {:keys [filename url error]} entry
        file? (and entry (not error))]
    [:div.ui.segment
     [:h4.ui.dividing.header "Uploaded Article PDFS (ZIP)"]
     [:p "Generate a zip file with all pdfs uploaded to articles in this project. PDFs are named by the sysrev article-id of their corresponding article."]
     [:div.eight.wide.field
      [:button.ui.tiny.fluid.primary.labeled.icon.button
       {:on-click (when-not running? (util/wrap-user-event #(dispatch [:action action])))
        :class (css [running? "loading"])}
       [:i.hdd.icon] "Generate"]]
     (when-not error
       [:div.field>div.ui.center.aligned.segment.file-download
        {:style {:margin-top "1rem"}}
        (cond running?  [:span "Generating file..."]
              file?     [:a {:href url :target "_blank" :download filename}
                         [:i.outline.file.icon] " " filename]
              :else     [:span "<Generate file to download>"])])
     (when error
       [:div.field>div.ui.negative.message
        {:style {:margin-top "1rem"}}
        (or (-> error :message)
            "Sorry, an error occurred while generating the file.")])]))

(defn- Panel [child]
  [:div.project-content
   [:div.ui.two.column.stackable.grid.export-data
    [:div.column
     [:div.ui.segment
      [:h4.ui.dividing.header "Article Answers"]
      [:p "This provides a CSV file containing the label answers from all project members for each article."]
      [:p (str "Each row contains answers for one article. Values are combined from all user answers; enabling "
               (pr-str (-> label-settings-config :consensus :display))
               " for a label can ensure that user answers are identical.")]
      [:p "By default, includes all labeled articles except those in Conflict status; this can be customized from the Articles page."]
      [ProjectExportNavigateForm :article-answers]]
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
      [ProjectExportNavigateForm :articles-csv]]
     [ExportUploadedArticlePDFs]
     [ExportJSON]
     [ExportGroupLabelCSV]]]
   child])

(def-panel :project? true :panel panel
  :uri "/export" :params [project-id] :name project-export
  :on-route (do (dispatch [::set [] {}])
                (dispatch [:set-active-panel panel]))
  :content (fn [child] [Panel child]))
