(ns sysrev.views.panels.project.overview
  (:require
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-event-fx reg-sub trim-v]]
   [sysrev.views.article-list :refer [group-statuses]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.charts :refer [chart-container pie-chart bar-chart
                                get-canvas-context wrap-animate-options]]
   [sysrev.views.panels.project.public-labels :as public-labels]
   [sysrev.views.upload :refer [upload-container basic-text-button]]
   [sysrev.views.components :refer [with-ui-help-tooltip]]
   [sysrev.routes :refer [nav-scroll-top]]
   [sysrev.util :refer [full-size?]]
   [cljs-time.core :as t]
   [cljs-time.coerce :refer [from-date]]
   [sysrev.shared.util :refer [in?]]
   [clojure.string :as str])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn nav-article-status [[inclusion group-status]]
  (nav-scroll-top "/project/articles")
  (dispatch [:public-labels/reset-filters [:group-status :inclusion-status]])
  (dispatch [:public-labels/set-group-status group-status])
  (dispatch [:public-labels/set-inclusion-status inclusion]))

(defn- label-status-help-column [colors]
  (let [scounts @(subscribe [:project/status-counts])
        scount #(get scounts % 0)
        bstyle (fn [color]
                 {:border (str "1px solid " color)})]
    [:div.label-status-help
     [:div.ui.top.attached.small.green.button.grouped-header
      "Include"]
     [:div.ui.bottom.attached.small.basic.buttons
      [:a.ui.button
       {:on-click #(nav-article-status [true :determined])}
       (str "Full (" (+ (scount [:consistent true])
                        (scount [:resolved true])) ")")]
      [:a.ui.button
       {:on-click #(nav-article-status [true :single])}
       (str "Partial (" (scount [:single true]) ")")]]
     [:div.ui.top.attached.small.orange.button.grouped-header
      "Exclude"]
     [:div.ui.bottom.attached.small.basic.buttons
      [:a.ui.button
       {:on-click #(nav-article-status [false :determined])}
       (str "Full (" (+ (scount [:consistent false])
                        (scount [:resolved false])) ")")]
      [:a.ui.button
       {:on-click #(nav-article-status [false :single])}
       (str "Partial (" (scount [:single false]) ")")]]
     [:div.ui.small.basic.buttons
      [:a.ui.button
       {:style (merge (bstyle (:red colors)))
        :on-click #(nav-article-status [nil :conflict])}
       (str "Conflict (" (scount [:conflict nil]) ")")]
      [:a.ui.button
       {:style (merge (bstyle (:purple colors)))
        :on-click #(nav-article-status [nil :resolved])}
       (str "Resolved (" (+ (scount [:resolved true])
                            (scount [:resolved false])) ")")]]]))

(defn- project-summary-box []
  (let [{:keys [reviewed unreviewed total]}
        @(subscribe [:project/article-counts])
        scounts @(subscribe [:project/status-counts])
        scount #(get scounts % 0)
        colors {:grey "rgba(160,160,160,0.5)"
                :green "rgba(33,186,69,0.55)"
                :dim-green "rgba(33,186,69,0.35)"
                :orange "rgba(242,113,28,0.55)"
                :dim-orange "rgba(242,113,28,0.35)"
                :red "rgba(230,30,30,0.6)"
                :blue "rgba(30,100,230,0.5)"
                :purple "rgba(146,29,252,0.5)"}
        statuses [:single :consistent :conflict :resolved]]
    [:div.project-summary
     [:div.ui.grey.segment
      [:h4.ui.center.aligned.dividing.header
       (str reviewed " articles reviewed of " total " total")]
      (with-loader [[:project]] {:dimmer :fixed}
        [:div.ui.two.column.stackable.middle.aligned.grid.pie-charts
         [:div.row
          [:div.column.pie-chart
           [chart-container pie-chart nil
            [["Include (Full)"
              (+ (scount [:consistent true])
                 (scount [:resolved true]))
              (:green colors)]
             ["Include (Partial)"
              (scount [:single true])
              (:dim-green colors)]
             ["Exclude (Full)"
              (+ (scount [:consistent false])
                 (scount [:resolved false]))
              (:orange colors)]
             ["Exclude (Partial)"
              (scount [:single false])
              (:dim-orange colors)]
             ["Conflicting"
              (scount [:conflict nil])
              (:red colors)]]
            #(nav-article-status
              (nth [[true :determined]
                    [true :single]
                    [false :determined]
                    [false :single]
                    [nil :conflict]] %))]]
          [:div.column.pie-chart-help
           [label-status-help-column colors]]]])]]))

(def file-types {"doc" "word"
                 "docx" "word"
                 "pdf" "pdf"
                 "xlsx" "excel"
                 "xls" "excel"
                 "gz" "archive"
                 "tgz" "archive"
                 "zip" "archive"})

(defn- get-file-class [fname]
  (get file-types (-> fname (str/split #"\.") last) "text"))

(defn- get-file-url [key name]
  (str "/api/files/" key "/" name))

(reg-sub
 ::editing-files
 (fn [[_ panel]]
   [(subscribe [:panel-field [:editing] panel])])
 (fn [[article-id]] article-id))

(reg-event-fx
 ::editing-files
 [trim-v]
 (fn [_ [value]]
   {:dispatch [:set-panel-field [:editing] value]}))

(defn- updater [panel-key f]
  (let [v (subscribe [panel-key])]
    [v #(dispatch [panel-key (f @v)])]))

(defn- toggler [panel-key] (updater panel-key not))

(defn project-files-box []
  (let [[editing-files toggle-editing] (toggler ::editing-files)
        files (subscribe [:project/files])]
    (letfn [(show-date [file]
              (let [date (from-date (:upload-time file))
                    parts (mapv #(% date) [t/month t/day t/year])]
                (apply goog.string/format "%d/%d/%d" parts)))
            (delete-file [file-id] (dispatch [:action [:files/delete-file file-id]]))
            (pull-files [] (dispatch [:fetch [:project/files]]))]
      (fn []
        [:div.ui.grey.segment.project-files
         [:h4.header "Project Documents"]
         [:div.ui.middle.aligned.celled.list
          (doall
           (concat
            (->>
             @files
             (map
              (fn [file]
                [:div.icon.item {:key (:file-id file)}
                 [:div.right.floated.content
                  [:div.ui.small.label (show-date file)]]
                 (if @editing-files
                   [:i.ui.middle.aligned.large.red.circle.remove.icon
                    {:on-click #(delete-file (:file-id file))
                     :style {:cursor "pointer"}}]
                   [:i.ui.middle.aligned.outline.blue.file.icon
                    {:class (get-file-class (:name file))}])
                 [:div.content.file-link
                  [:a {:href (get-file-url (:file-id file) (:name file))
                       :target "_blank"
                       :download (:name file)}
                   (:name file)]]])))
            (list [:div.item {:key "celled list filler"}])))
          [:div.upload-container
           [upload-container
            basic-text-button "/api/files/upload" pull-files "Upload document"]
           [:div.ui.right.floated.small.basic.icon.button
            {:on-click toggle-editing
             :class (when @editing-files "red")}
            [:i.ui.blue.pencil.icon]]]]]))))

(defn user-summary-chart []
  (let [visible-user-ids (->> @(subscribe [:project/member-user-ids])
                              (sort-by #(deref (subscribe [:member/article-count %])) >))
        user-names (->> visible-user-ids
                        (mapv #(deref (subscribe [:user/display %]))))
        includes   (->> visible-user-ids
                        (mapv #(deref (subscribe [:member/include-count %]))))
        excludes   (->> visible-user-ids
                        (mapv #(deref (subscribe [:member/exclude-count %]))))
        yss [includes excludes]
        ynames ["Include" "Exclude"]]
    [:div.ui.grey.segment
     [:h4.ui.dividing.header
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        "Member Activity"]]]
     (with-loader [[:project]] {:dimmer :fixed}
       [chart-container
        bar-chart (str (+ 35 (* 15 (count visible-user-ids))) "px")
        user-names ynames yss
        ["rgba(33,186,69,0.55)"
         "rgba(242,113,28,0.55)"]])]))

(defn recent-progress-chart []
  (let [font-color (if (= (:ui-theme @(subscribe [:self/settings]))
                          "Dark")
                     "#dddddd" "#222222")
        progress (reverse @(subscribe [:project/progress-counts]))
        n-total (-> @(subscribe [:project/article-counts]) :total)
        make-chart
        (fn [id xvals xlabels]
          (let [context (get-canvas-context id)
                xdiff (js/Math.abs (- (last xvals) (first xvals)))
                chart-data
                {:type "line"
                 :data {:labels xlabels
                        :datasets [{:fill "origin"
                                    :backgroundColor "rgba(30,100,250,0.2)"
                                    :lineTension 0
                                    :data (vec xvals)}]}
                 :options
                 (wrap-animate-options
                  {:legend {:display false}
                   :scales
                   {:xAxes [{:ticks
                             {:fontColor font-color
                              :autoSkip true
                              :callback
                              (fn [value idx values]
                                (if (or (= 0 (mod idx 5))
                                        (= idx (dec (count values))))
                                  value ""))}
                             :scaleLabel {:fontColor font-color}}]
                    :yAxes [{:scaleLabel {:display true
                                          :labelString "Articles Completed"
                                          :fontColor font-color}
                             :ticks
                             {:fontColor font-color
                              :suggestedMin (max 0
                                                 (int (- (first xvals)
                                                         (* xdiff 0.15))))
                              :suggestedMax (min n-total
                                                 (int (+ (last xvals)
                                                         (* xdiff 0.15))))}}]}
                   :responsive true})}]
            (js/Chart. context (clj->js chart-data))))]
    [:div.ui.grey.segment
     [:h4.ui.dividing.header
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        "Recent Progress"]]]
     (with-loader [[:project]] {:dimmer :fixed}
       [chart-container
        make-chart nil
        (->> progress (mapv :completed)
             #_ (mapv #(* 100.0 (/ % n-total))))
        (->> progress (mapv :day)
             (mapv #(->> (str/split % #"\-") (drop 1) (str/join "-"))))])]))

(defn project-overview-panel []
  [:div.ui.two.column.stackable.grid.project-overview
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     [recent-progress-chart]]
    [:div.ui.column
     [user-summary-chart]
     [project-files-box]]]])

(defmethod panel-content [:project :project :overview] []
  (fn [child]
    [:div.project-content
     [project-overview-panel]
     child]))