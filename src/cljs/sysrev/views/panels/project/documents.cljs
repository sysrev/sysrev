(ns sysrev.views.panels.project.documents
  (:require [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.coerce :refer [from-date]]
            [re-frame.core :refer
             [subscribe dispatch reg-event-db reg-event-fx reg-sub trim-v]]
            [sysrev.state.ui :refer [get-panel-field set-panel-field]]
            [sysrev.views.components.core :as ui]
            [sysrev.macros :refer-macros [with-loader]]))

(def file-types {"doc"    "word"
                 "docx"   "word"
                 "pdf"    "pdf"
                 "xlsx"   "excel"
                 "xls"    "excel"
                 "gz"     "archive"
                 "tgz"    "archive"
                 "zip"    "archive"})

(defn- get-file-class [fname]
  (get file-types (-> fname (str/split #"\.") last) "text"))

(defn- get-file-url [project-id key name]
  (str "/api/files/" project-id "/download/" key))

(reg-sub ::editing-files
         :<- [:panel-field :editing]
         (fn [editing] editing))

(reg-event-db ::editing-files [trim-v]
              (fn [db [value]]
                (set-panel-field db :editing value)))

(defn- updater [key update-value]
  (let [value (subscribe [key])]
    [value #(dispatch [key (update-value @value)])]))

(defn- toggler [key] (updater key not))

(defn ProjectFilesBox []
  (let [project-id @(subscribe [:active-project-id])
        [editing-files toggle-editing] (toggler ::editing-files)
        files (subscribe [:project/files])
        member? @(subscribe [:self/member? project-id])]
    (letfn [(show-date [file]
              (let [date (from-date (:created file))
                    parts (mapv #(% date) [t/month t/day t/year])]
                (apply goog.string/format "%d/%d/%d" parts)))
            (delete-file [file-key] (dispatch [:action [:project/delete-file project-id file-key]]))
            (pull-files [] (dispatch [:fetch [:project/files project-id]]))]
      (with-loader [[:project project-id]
                    [:project/files project-id]] {}
        (when (or member? (seq @files))
          [:div.ui.segment.project-files
           [:h4.ui.dividing.header "Project Documents"]
           [:div.ui.middle.aligned.divided.list
            (doall
             (for [{:keys [key filename] :as file} @files]
               [:div.icon.item {:key key}
                [:div.right.floated.content
                 [:div.ui.small.label (show-date file)]]
                (if @editing-files
                  [:i.ui.middle.aligned.red.times.circle.outline.icon
                   {:on-click #(delete-file key)
                    :style {:cursor "pointer"}}]
                  [:i.ui.middle.aligned.outline.blue.file.icon
                   {:class (get-file-class filename)}])
                [:div.content.file-link
                 [:a {:href (get-file-url project-id key filename) :target "_blank"
                      :download filename}
                  filename]]]))]
           (when member?
             [:div.ui.two.column.middle.aligned.grid.upload-grid
              [:div.left.aligned.column.upload-container
               [ui/UploadButton
                (str "/api/files/" project-id "/upload")
                pull-files
                "Upload document"]]
              [:div.right.aligned.column
               [:div.ui.right.floated.small.icon.button
                {:on-click toggle-editing
                 :style (when @editing-files {:border "1px solid red"
                                              :margin "-1px"})}
                [:i.ui.blue.pencil.icon]]]])])))))
