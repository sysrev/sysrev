(ns sysrev.views.panels.project.overview
  (:require [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.coerce :refer [from-date]]
            [reagent.core :as r]
            [sysrev.charts.chartjs :as chartjs]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-event-fx reg-sub trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [active-project-id project-uri]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :as ui]
            [sysrev.views.charts :as charts]
            [sysrev.views.panels.project.articles]
            [sysrev.views.upload :refer [upload-container basic-text-button]]
            [sysrev.util :refer [full-size? random-id continuous-update-until]]
            [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def initial-state {})
(defonce state (r/cursor app-db [:state :panels :project :overview]))
(defonce important-terms-tab (r/cursor state [:important-terms-tab]))

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})

(reg-event-fx
 :overview/reset-state!
 [trim-v]
 (fn [_]
   (reset! state initial-state)
   {}))

(defn nav-article-status [[inclusion group-status]]
  (when-let [project-id @(subscribe [:active-project-id])]
    (dispatch [:navigate [:project :project :articles]
               {:project-id project-id}])
    (dispatch [:project-articles/reset-filters [:group-status :inclusion-status]])
    (dispatch [:project-articles/set-group-status group-status])
    (dispatch [:project-articles/set-inclusion-status inclusion])))

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

(defn- ReviewStatusBox []
  (let [project-id @(subscribe [:active-project-id])
        {:keys [reviewed unreviewed total]}
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
     [:div.ui.segment
      [:h4.ui.dividing.header
       "Review Status"]
      (with-loader [[:project project-id]]
        {:dimmer :fixed}
        [:div
         [:h4.ui.center.aligned.header
          (str reviewed " articles reviewed of " total " total")]
         [:div.ui.two.column.stackable.middle.aligned.grid.pie-charts
          [:div.row
           [:div.column.pie-chart
            [charts/pie-chart
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
            [label-status-help-column colors]]]]])]]))

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

(defn- get-file-url [project-id key name]
  (str "/api/files/" project-id "/download/" key "/" name))

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

(defn ProjectFilesBox []
  (let [project-id @(subscribe [:active-project-id])
        [editing-files toggle-editing] (toggler ::editing-files)
        files (subscribe [:project/files])
        member? @(subscribe [:self/member? project-id])]
    (letfn [(show-date [file]
              (let [date (from-date (:upload-time file))
                    parts (mapv #(% date) [t/month t/day t/year])]
                (apply goog.string/format "%d/%d/%d" parts)))
            (delete-file [file-id] (dispatch [:action [:project/delete-file project-id file-id]]))
            (pull-files [] (dispatch [:fetch [:project/files project-id]]))]
      (fn []
        [:div.ui.segment.project-files
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
                   [:i.ui.middle.aligned.red.times.circle.outline.icon
                    {:on-click #(delete-file (:file-id file))
                     :style {:cursor "pointer"}}]
                   [:i.ui.middle.aligned.outline.blue.file.icon
                    {:class (get-file-class (:name file))}])
                 [:div.content.file-link
                  [:a {:href (get-file-url project-id (:file-id file) (:name file))
                       :target "_blank"
                       :download (:name file)}
                   (:name file)]]])))
            (if member?
              [[:div.item {:key "celled list filler"}]]
              [])))
          (when member?
            [:div.upload-container
             [upload-container
              basic-text-button
              (str "/api/files/" project-id "/upload")
              pull-files
              "Upload document"]
             [:div.ui.right.floated.small.icon.button
              {:on-click toggle-editing
               :style (when @editing-files {:border "1px solid red"
                                            :margin "-1px"})}
              [:i.ui.blue.pencil.icon]]])]]))))

(defn MemberActivityChart []
  (let [project-id @(subscribe [:active-project-id])
        visible-user-ids (->> @(subscribe [:project/member-user-ids])
                              (sort-by #(deref (subscribe [:member/article-count %])) >))
        user-names (->> visible-user-ids
                        (mapv #(deref (subscribe [:user/display %]))))
        includes   (->> visible-user-ids
                        (mapv #(deref (subscribe [:member/include-count %]))))
        excludes   (->> visible-user-ids
                        (mapv #(deref (subscribe [:member/exclude-count %]))))
        yss [includes excludes]
        ynames ["Include" "Exclude"]]
    [:div.ui.segment
     [:h4.ui.dividing.header
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        "Member Activity"]]]
     (with-loader [[:project project-id]] {:dimmer :fixed}
       [charts/bar-chart (+ 35 (* 15 (count visible-user-ids)))
        user-names ynames yss
        ["rgba(33,186,69,0.55)"
         "rgba(242,113,28,0.55)"]])]))

(defn RecentProgressChart []
  (let [project-id @(subscribe [:active-project-id])
        font-color (if (= (:ui-theme @(subscribe [:self/settings]))
                          "Dark")
                     "#dddddd" "#222222")
        progress (reverse @(subscribe [:project/progress-counts]))
        n-total (-> @(subscribe [:project/article-counts]) :total)
        xvals (->> progress (mapv :completed))
        xlabels (->> progress (mapv :day)
                     (mapv #(->> (str/split % #"\-") (drop 1) (str/join "-"))))
        xdiff (js/Math.abs (- (last xvals) (first xvals)))
        data
        {:labels xlabels
         :datasets [{:fill "origin"
                     :backgroundColor "rgba(30,100,250,0.2)"
                     :lineTension 0
                     :data (vec xvals)}]}
        options
        (charts/wrap-animate-options
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
          :responsive true})]
    [:div.ui.segment
     [:h4.ui.dividing.header
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        "Recent Progress"]]]
     (with-loader [[:project project-id]] {:dimmer :fixed}
       [chartjs/line
        {:data data
         :options options}])]))

(defn LabelPredictionsInfo []
  (when (not-empty @(subscribe [:project/predict]))
    (let [updated @(subscribe [:predict/update-time])
          labeled @(subscribe [:predict/labeled-count])
          total @(subscribe [:predict/article-count])]
      [:div.ui.segment
       [:h4.ui.dividing.header
        "Label Predictions"]
       [:p "Last updated: " (str updated)]
       [:p "Trained from " (str labeled)
        " labeled articles; " (str total)
        " article predictions loaded"]])))

(def-data :project/important-terms
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :importance)))
  :uri (fn [project-id] "/api/important-terms")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] result]
    {:db (assoc-in db [:data :project project-id :importance] result)}))

(reg-sub
 :project/important-terms
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ entity-type project-id]]
   (if (nil? entity-type)
     (get-in project [:importance :terms])
     (get-in project [:importance :terms entity-type]))))

(reg-sub
 :project/important-terms-loading?
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ entity-type project-id]]
   (true? (get-in project [:importance :loading]))))

(defonce polling-important-terms? (r/atom false))

(defn poll-important-terms [project-id]
  (when (not @polling-important-terms?)
    (reset! polling-important-terms? true)
    (dispatch [:fetch [:project/important-terms project-id]])
    (let [server-loading? (subscribe [:project/important-terms-loading?])
          item [:project/important-terms project-id]
          updating? (fn [] (or @server-loading?
                               (loading/item-loading? item)))]
      (continuous-update-until #(dispatch [:fetch item])
                               #(not (updating?))
                               #(reset! polling-important-terms? false)
                               1000))))

(defn ImportantTermsChart [{:keys [entity data loading?]} title]
  (when (not-empty data)
    (let [project-id @(subscribe [:active-project-id])
          height (+ 35 (* 10 (count data)))]
      (let [entries (->> data (sort-by :instance-count >))
            labels (mapv :instance-name entries)
            counts (mapv :instance-count entries)]
        [charts/bar-chart height labels ["count"] [counts]
         ["rgba(33,186,69,0.55)"]
         {:legend {:display false}}]))))

(defn KeyTerms []
  (let [active-tab (or @important-terms-tab :mesh)
        project-id @(subscribe [:active-project-id])
        terms @(subscribe [:project/important-terms])
        loading? @(subscribe [:project/important-terms-loading?])
        {:keys [mesh chemical gene]} terms]
    (with-loader [[:project project-id]
                  [:project/important-terms project-id]]
      {}
      (when (or (not-empty terms) loading?)
        [:div.ui.segment
         [:h4.ui.dividing.header
          [:div.ui.two.column.middle.aligned.grid
           [:div.ui.left.aligned.column
            "Important Terms"]]]
         (with-loader [[:project project-id]
                       [:project/important-terms project-id]]
           {:dimmer :fixed
            :force-dimmer loading?}
           [:div
            (when loading?
              (poll-important-terms project-id))
            [ui/tabbed-panel-menu
             [{:tab-id :mesh
               :content "MeSH"
               :action #(reset! important-terms-tab :mesh)}
              {:tab-id :chemical
               :content "Compounds"
               :action #(reset! important-terms-tab :chemical)}
              {:tab-id :gene
               :content "Genes"
               :action #(reset! important-terms-tab :gene)}]
             active-tab
             "important-term-tab"]
            (let [[data title]
                  (case active-tab
                    :mesh      [mesh "Important Mesh Terms"]
                    :chemical  [chemical "Important Compounds"]
                    :gene      [gene "Important Genes"])]
              [ImportantTermsChart
               {:entity active-tab, :data data, :loading? loading?}
               title])])]))))

(defn short-labels-vector
  "Given a set of label-counts, get the set of short-labels"
  [processed-label-counts]
  ((comp (partial into []) sort set (partial mapv :short-label))
   processed-label-counts))

(defn processed-label-color-map
  "Given a set of label-counts, generate a color map"
  [processed-label-counts]
  (let [short-labels (short-labels-vector processed-label-counts)
        ;; need to account for the fact that this fn can handle empty datasets
        color-count (max 0 (- (count short-labels) 1))
        palette (nth charts/paul-tol-colors color-count)
        color-map (zipmap short-labels palette)]
    (mapv (fn [label palette]
            {:short-label label :color palette})
          short-labels palette)))

(reg-sub
 ::label-counts
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:label-counts project)))

(def-data :project/label-counts
  :loaded?
  (fn [db project-id]
    (-> (get-in db [:data :project project-id])
        (contains? :label-counts)))
  :uri (fn [] "/api/charts/label-count-data")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [data]}]
    {:db (assoc-in db [:data :project project-id :label-counts]
                   data)}))

(defn LabelCountChart [label-ids processed-label-counts]
  (let [color-filter (r/atom #{})]
    (fn [label-ids processed-label-counts]
      (when (not (empty? processed-label-counts))
        (let [font-color (charts/graph-text-color)
              processed-label-counts
              (sort-by
               #((into {} (map-indexed (fn [i e] [e i]) label-ids))
                 (:label-id %))
               processed-label-counts)
              filtered-color? #(contains? @color-filter %)
              color-filter-fn
              (fn [items]
                (filterv #(not (filtered-color? (:color %))) items))
              labels (->> processed-label-counts
                          color-filter-fn
                          (mapv :value))
              counts (->> processed-label-counts
                          color-filter-fn
                          (mapv :count))
              background-colors (->>  processed-label-counts
                                      color-filter-fn
                                      (mapv :color))
              color-map (processed-label-color-map processed-label-counts)
              short-label->label-uuid
              (fn [short-label]
                @(subscribe [:label/id-from-short-label short-label]))
              legend-labels
              (->> color-map
                   (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids))
                              (short-label->label-uuid (:short-label %))))
                   (mapv (fn [{:keys [short-label color]}]
                           {:text short-label :fillStyle color
                            :hidden (filtered-color? color)})))
              data {:labels labels
                    :datasets [{:data (if (empty? counts)
                                        [0]
                                        counts)
                                :backgroundColor (if (empty? counts)
                                                   ["#000000"]
                                                   background-colors)}]}
              options (charts/wrap-disable-animation
                       {:scales
                        {:xAxes
                         [{:display true
                           :scaleLabel {:fontColor font-color
                                        :display false
                                        :padding {:top 200
                                                  :bottom 200}}
                           :stacked false
                           :ticks {:fontColor font-color
                                   :suggestedMin 0
                                   :callback (fn [value idx values]
                                               (if (or (= 0 (mod idx 5))
                                                       (= idx (dec (count values))))
                                                 value ""))}}]
                         ;; this is actually controlling the labels
                         :yAxes
                         [{:maxBarThickness 10
                           :scaleLabel {:fontColor font-color}
                           :ticks {:fontColor font-color}}]}
                        :legend
                        {:labels
                         {:generateLabels (fn [chart]
                                            (clj->js legend-labels))
                          :fontColor font-color}
                         :onClick
                         (fn [e legend-item]
                           (let [current-legend-color
                                 (:fillStyle (js->clj legend-item
                                                      :keywordize-keys true))
                                 enabled? (not (filtered-color? current-legend-color))]
                             #_ (.preventDefault e)
                             (if enabled?
                               ;; filter out the associated data points
                               (swap! color-filter #(conj % current-legend-color))
                               ;; the associated data points should no longer be filtered out
                               (swap! color-filter #(disj % current-legend-color)))))}})
              height (charts/label-count->chart-height (count labels))]
          [:div.ui.segment
           [:h4.ui.dividing.header "Member Label Counts"]
           [chartjs/horizontal-bar
            {:data data
             :height height
             :options (merge options)}]])))))

(defn LabelCounts []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/label-counts project-id]] {}
      (let [label-ids @(subscribe [:project/label-ids])
            processed-label-counts @(subscribe [::label-counts])]
        [LabelCountChart label-ids processed-label-counts]))))

(reg-sub
 ::prediction-histograms
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:histograms project)))

(def-data :project/prediction-histograms
  :loaded?
  (fn [db project-id]
    (-> (get-in db [:data :project project-id])
        (contains? :histograms)))
  :uri (fn [] "/api/prediction-histograms")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [prediction-histograms]}]
    {:db (assoc-in db [:data :project project-id :histograms]
                   prediction-histograms)}))

(defn PredictionHistogramChart []
  (let [font-color (charts/graph-text-color)
        prediction-histograms
        @(subscribe [::prediction-histograms])
        labels
        (-> prediction-histograms
            vals
            merge
            flatten
            (->> (sort-by :score)
                 (mapv :score))
            set
            (->> (into [])))
        process-histogram-fn
        (fn [histogram]
          (let [score-count-map (zipmap (mapv :score histogram)
                                        (mapv :count histogram))]
            (mapv #(if-let [label-count (get score-count-map %)]
                     label-count
                     0) labels)))
        processed-reviewed-include-histogram
        (process-histogram-fn
         (:reviewed-include-histogram prediction-histograms))
        processed-reviewed-exclude-histogram
        (process-histogram-fn
         (:reviewed-exclude-histogram prediction-histograms))
        processed-unreviewed-histogram
        (process-histogram-fn
         (:unreviewed-histogram prediction-histograms))
        datasets
        (cond-> []
          (not (every? #(= 0 %) processed-reviewed-include-histogram))
          (merge {:label "Reviewed - Include"
                  :data ;;(mapv (partial * 2) (into [] (range 1 (+ (count labels) 1))))
                  processed-reviewed-include-histogram
                  :backgroundColor (:green colors)})
          (not (every? #(= 0 %) processed-reviewed-exclude-histogram))
          (merge {:label "Reviewed - Exclude"
                  :data processed-reviewed-exclude-histogram
                  ;;(mapv (partial * 1) (into [] (range 1 (+ (count labels) 1))))
                  :backgroundColor (:red colors)})
          (not (every? #(= 0 %) processed-unreviewed-histogram))
          (merge {:label "Unreviewed"
                  :data processed-unreviewed-histogram
                  ;;(mapv (partial * 3) (into [] (range 1 (+ (count labels) 1))))
                  :backgroundColor (:orange colors)}))]
    [:div.ui.segment
     [:h4.ui.dividing.header "Prediction Histograms"]
     [chartjs/bar
      {:data {:labels labels
              :datasets datasets}
       :options {:scales
                 {:xAxes [{:stacked true
                           :ticks {:fontColor font-color}
                           :scaleLabel {:fontColor font-color}}]
                  :yAxes [{:ticks {:fontColor font-color}
                           :scaleLabel {:fontColor font-color}}]}
                 :legend {:labels {:fontColor font-color}}}
       :height 170}]]))

(defn PredictionHistogram []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/prediction-histograms project-id]] {}
      [PredictionHistogramChart])))

(defn ProjectOverviewContent []
  [:div.project-content
   [:div.ui.two.column.stackable.grid.project-overview
    [:div.ui.row
     [:div.ui.column
      [ReviewStatusBox]
      [RecentProgressChart]
      [LabelPredictionsInfo]
      [PredictionHistogram]]
     [:div.ui.column
      [MemberActivityChart]
      [ProjectFilesBox]
      [KeyTerms]
      [LabelCounts]]]]])

(defn ProjectOverviewPanel [child]
  (let [project-id @(subscribe [:active-project-id])
        has-articles? @(subscribe [:project/has-articles?])]
    [:div.project-content
     (if (false? has-articles?)
       (do (nav/nav-redirect
            (project-uri project-id "/add-articles")
            :scroll-top? true)
           [:div])
       [ProjectOverviewContent])
     child]))

(defmethod panel-content [:project :project :overview] []
  (fn [child] [ProjectOverviewPanel child]))
