(ns sysrev.views.panels.project.overview
  (:require [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.coerce :refer [from-date]]
            [reagent.core :as r]
            [sysrev.charts.chartjs :as chartjs]
            [re-frame.core :refer
             [subscribe dispatch reg-event-db reg-event-fx reg-sub trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.loading :as loading]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [active-project-id project-uri]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.charts :as charts]
            [sysrev.views.panels.project.articles :as articles]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.util :as util :refer [wrap-user-event]]
            [sysrev.shared.util :as sutil :refer [in?]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

(setup-panel-state panel [:project :project :overview])

#_ (defonce important-terms-tab (r/cursor state [:important-terms-tab]))

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})

(defn unpad-chart [unpad-em content]
  (let [margin-top (if (sequential? unpad-em) (first unpad-em) unpad-em)
        margin-bottom (if (sequential? unpad-em) (second unpad-em) unpad-em)
        to-css #(str "-" % "em")]
    [:div {:style {:margin-top (to-css margin-top)
                   :margin-bottom (to-css margin-bottom)}}
     content]))

(defn nav-article-status [[inclusion group-status]]
  (when-let [project-id @(subscribe [:active-project-id])]
    (articles/load-consensus-settings :status group-status
                                      :inclusion inclusion)))

(defn- label-status-help-column [colors]
  (let [scounts @(subscribe [:project/status-counts])
        scount #(get scounts % 0)
        color-border #(str "1px solid " (get colors %))
        wrap-nav #(wrap-user-event % :prevent-default true)]
    [:div.label-status-help
     [:div.ui.segments
      [:div.ui.attached.small.segment.status-header.green
       "Include"]
      [:div.ui.attached.segment.status-buttons.with-header
       [:div.ui.small.basic.fluid.buttons
        [:a.ui.button.include-full-button
         {:on-click (wrap-nav #(nav-article-status [true :determined]))}
         (str "Full (" (+ (scount [:consistent true])
                          (scount [:resolved true])) ")")]
        [:a.ui.button.include-partial-button
         {:on-click (wrap-nav #(nav-article-status [true :single]))}
         (str "Partial (" (scount [:single true]) ")")]]]]
     [:div.ui.segments
      [:div.ui.attached.small.segment.status-header.orange
       "Exclude"]
      [:div.ui.attached.segment.status-buttons.with-header
       [:div.ui.small.basic.fluid.buttons
        [:a.ui.button.exclude-full-button
         {:on-click (wrap-nav #(nav-article-status [false :determined]))}
         (str "Full (" (+ (scount [:consistent false])
                          (scount [:resolved false])) ")")]
        [:a.ui.button.exclude-partial-button
         {:on-click (wrap-nav #(nav-article-status [false :single]))}
         (str "Partial (" (scount [:single false]) ")")]]]]
     [:div.ui.segments {:style {:border-color "rgba(0,0,0,0.0)"}}
      [:div.ui.attached.segment.status-buttons.no-header
       [:div.ui.small.basic.fluid.buttons
        [:a.ui.button.conflict-button
         {:style {:border-left (color-border :red)
                  :border-top (color-border :red)
                  :border-bottom (color-border :red)}
          :on-click (wrap-nav #(nav-article-status [nil :conflict]))}
         (str "Conflict (" (scount [:conflict nil]) ")")]
        [:a.ui.button.resolve-button
         {:style {:border-right (color-border :purple)
                  :border-top (color-border :purple)
                  :border-bottom (color-border :purple)}
          :on-click (wrap-nav #(nav-article-status [nil :resolved]))}
         (str "Resolved (" (+ (scount [:resolved true])
                              (scount [:resolved false])) ")")]]]]]))

(defn- ReviewStatusBox []
  (let [project-id @(subscribe [:active-project-id])
        {:keys [reviewed unreviewed total]}
        @(subscribe [:project/article-counts])
        scounts @(subscribe [:project/status-counts])
        {:keys [unlimited-reviews]} @(subscribe [:project/settings])
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
    (when-not unlimited-reviews
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
              [label-status-help-column colors]]]]])]])))



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
        ynames ["Include" "Exclude"]
        invite-url @(subscribe [:project/invite-url])
        invite? (and invite-url (or @(subscribe [:self/member?])
                                    @(subscribe [:user/admin?])))]
    [:div.ui.segments
     [:div.ui.segment
      [:h4.ui.dividing.header "Member Activity"]
      (with-loader [[:project project-id]] {:dimmer :fixed}
        [unpad-chart [0.7 0.5]
         [charts/bar-chart (* 2 (+ 35 (* 12 (count visible-user-ids))))
          user-names ynames yss
          :colors ["rgba(33,186,69,0.55)"
                   "rgba(242,113,28,0.55)"]
          :on-click #(articles/load-member-label-settings
                      (nth visible-user-ids %))]])
      (when invite?
        [:h4.ui.dividing.header {:style {:margin-top "1.5em"}}
         "Invite others to join"])
      (when invite?
        [:div.ui.fluid.action.input
         [:input#invite-url.ui.input {:readOnly true
                                      :value invite-url}]
         [ui/ClipboardButton "#invite-url" "Copy Invite Link"]])]]))

(defn RecentProgressChart []
  (let [project-id @(subscribe [:active-project-id])
        font (charts/graph-font-settings)
        progress (reverse @(subscribe [:project/progress-counts]))
        n-total (-> @(subscribe [:project/article-counts]) :total)
        xvals (->> progress (mapv :labeled))]
    (when (> (last xvals) (->> xvals (drop 4) first))
      (let [xlabels (->> progress (mapv :day)
                         (mapv #(->> (str/split % #"\-") (drop 1) (str/join "-"))))
            xdiff (js/Math.abs (- (last xvals) (first xvals)))
            data
            {:labels xlabels
             :datasets [{:fill "origin"
                         :backgroundColor "rgba(30,100,250,0.2)"
                         :lineTension 0
                         :data (vec xvals)}]}
            options
            (charts/wrap-default-options
             {:legend {:display false}
              :scales
              {:xAxes [{:ticks
                        (->> {:autoSkip true
                              :callback (fn [value idx values]
                                          (if (or (= 0 (mod idx 5))
                                                  (= idx (dec (count values))))
                                            value ""))}
                             (merge font))
                        :gridLines {:color (charts/graph-border-color)}
                        :scaleLabel font}]
               :yAxes [{:ticks
                        (->> {:suggestedMin (max 0
                                                 (int (- (first xvals)
                                                         (* xdiff 0.15))))
                              :suggestedMax (min n-total
                                                 (int (+ (last xvals)
                                                         (* xdiff 0.15))))}
                             (merge font))
                        :gridLines {:color (charts/graph-border-color)}
                        :scaleLabel (->> {:display true
                                          :labelString "User Articles Labeled"
                                          :fontSize 14}
                                         (merge font))}]}})]
        [:div.ui.segment
         [:h4.ui.dividing.header "Recent Progress"]
         (with-loader [[:project project-id]] {:dimmer :fixed}
           [:div {:style {:padding-top "0.5em"
                          :margin-bottom "-0.6em"}}
            [chartjs/line {:data data :options options :height 275}]])]))))

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
  :prereqs (fn [project-id] [[:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] result]
    {:db (assoc-in db [:data :project project-id :importance] result)}))

(reg-sub :project/important-terms
         (fn [[_ _ project-id]] (subscribe [:project/raw project-id]))
         (fn [project [_ entity-type project-id]]
           (cond-> (get-in project [:importance :terms])
             entity-type (get entity-type))))

(reg-sub :project/important-terms-loading?
         (fn [[_ _ project-id]] (subscribe [:project/raw project-id]))
         (fn [project [_ entity-type project-id]]
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
      (util/continuous-update-until #(dispatch [:fetch item])
                                    #(not (updating?))
                                    #(reset! polling-important-terms? false)
                                    500))))

(defn ImportantTermsChart [{:keys [entity data loading?]}]
  (when (not-empty data)
    (let [project-id @(subscribe [:active-project-id])
          height (* 2 (+ 8 (* 10 (count data))))]
      (let [entries (->> data (sort-by :tfidf >))
            labels (->> entries (mapv :instance-name))
            scores (->> entries (mapv :tfidf) (mapv #(/ % 10000.0)))]
        [:div
         [charts/bar-chart height labels ["Relevance"] [scores]
          :colors ["rgba(33,186,69,0.55)"]
          :options {:legend {:display false}}
          :display-ticks false
          :log-scale true]]))))

(defn KeyTerms []
  (let [#_ active-tab #_ (or @important-terms-tab :mesh)
        project-id @(subscribe [:active-project-id])
        terms @(subscribe [:project/important-terms])
        ;; TODO: fix this for tests against staging.sysrev.com?
        ;;       seems to get stuck rendering loader if loading of terms fails
        loading? (if (= js/window.location.hostname "staging.sysrev.com")
                   false
                   @(subscribe [:project/important-terms-loading?]))
        {:keys [mesh #_ chemical #_ gene]} terms
        project? @(subscribe [:have? [:project project-id]])
        lapsed? @(subscribe [:project/subscription-lapsed?])]
    (with-loader [[:project project-id]
                  (when (and project? (not lapsed?))
                    [:project/important-terms project-id])] {}
      (when (or (not-empty terms) loading?)
        [:div.ui.segment
         [:h4.ui.dividing.header "Important MeSH Terms"]
         (with-loader [[:project project-id]
                       [:project/important-terms project-id]]
           {:dimmer :fixed :force-dimmer loading?}
           [:div
            (when @(subscribe [:project/important-terms-loading?])
              (poll-important-terms project-id))
            #_
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
            #_
            (let [data (get terms active-tab)]
              [ImportantTermsChart
               {:entity active-tab, :data data, :loading? loading?}])
            [unpad-chart [0.25 0.35]
             [ImportantTermsChart
              {:entity :mesh, :data mesh, :loading? loading?}]]])]))))

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
  :prereqs (fn [project-id] [[:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [data]}]
    {:db (assoc-in db [:data :project project-id :label-counts]
                   data)}))

(defn LabelCountChart [label-ids processed-label-counts]
  (let [color-filter (r/atom #{})]
    (fn [label-ids processed-label-counts]
      (when (not (empty? processed-label-counts))
        (let [font (charts/graph-font-settings)
              processed-label-counts
              (sort-by
               #((into {} (map-indexed (fn [i e] [e i]) label-ids))
                 (:label-id %))
               processed-label-counts)
              filtered-color? #(contains? @color-filter %)
              color-filter-fn
              (fn [items]
                (filterv #(not (filtered-color? (:color %))) items))
              entries (->> processed-label-counts
                           color-filter-fn)
              max-length (if (util/mobile?) 22 28)
              labels (->> entries
                          (mapv :value)
                          (mapv str)
                          (mapv #(if (<= (count %) max-length)
                                   % (str (subs % 0 (- max-length 2)) "..."))))
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
                           {:text short-label
                            :hidden (filtered-color? color)
                            :fillStyle color
                            :lineWidth 0})))
              data {:labels labels
                    :datasets [{:data (if (empty? counts)
                                        [0]
                                        counts)
                                :backgroundColor (if (empty? counts)
                                                   ["#000000"]
                                                   background-colors)}]}
              options (charts/wrap-default-options
                       {:scales
                        {:xAxes
                         [{:scaleLabel (->> {:display true
                                             :labelString "User Answers"}
                                            (merge font))
                           ;; :type "logarithmic"
                           :stacked false
                           :ticks (->> {:suggestedMin 0
                                        :callback (fn [value idx values]
                                                    (if (or (= 0 (mod idx 5))
                                                            (= idx (dec (count values))))
                                                      value ""))}
                                       (merge font))
                           :gridLines {:color (charts/graph-border-color)}}]
                         ;; this is actually controlling the labels
                         :yAxes
                         [{:maxBarThickness 12
                           :scaleLabel font
                           :ticks (->> {:padding 7} (merge font))
                           :gridLines {:drawTicks false
                                       :color (charts/graph-border-color)}}]}
                        :legend
                        {:labels
                         (->> {:generateLabels (fn [_] (clj->js legend-labels))}
                              (merge font))
                         :onClick
                         (fn [e legend-item]
                           (let [current-legend-color
                                 (:fillStyle (js->clj legend-item
                                                      :keywordize-keys true))
                                 enabled? (not (filtered-color? current-legend-color))]
                             (if enabled?
                               ;; filter out the associated data points
                               (swap! color-filter #(conj % current-legend-color))
                               ;; the associated data points should no longer be filtered out
                               (swap! color-filter #(disj % current-legend-color)))))}
                        :onClick
                        (fn [event elts]
                          (let [elts (-> elts js->clj)]
                            (when (and (coll? elts) (not-empty elts))
                              (when-let [idx (-> elts first (aget "_index"))]
                                (let [{:keys [label-id value]} (nth entries idx)]
                                  (articles/load-label-value-settings label-id value))))))}
                       :animate? false
                       :items-clickable? true)
              height (* 2 (+ 40
                             (* 10 (Math/round (/ (inc (count label-ids)) 3)))
                             (* 10 (count counts))))]
          [:div.ui.segment
           [:h4.ui.dividing.header
            (ui/with-ui-help-tooltip
              [:span "Answer Counts " util/nbsp [ui/ui-help-icon]]
              :help-content ["Number of user answers that contain each label value"])]
           [unpad-chart [0.6 0.4]
            [chartjs/horizontal-bar
             {:data data :height height :options options}]]])))))

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
  :prereqs (fn [project-id] [[:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [prediction-histograms]}]
    {:db (assoc-in db [:data :project project-id :histograms]
                   prediction-histograms)}))

(defn PredictionHistogramChart []
  (let [font (charts/graph-font-settings)
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
    (when-not (empty? datasets)
      [:div.ui.segment
       [:h4.ui.dividing.header "Prediction Histograms"]
       [unpad-chart [0.5 0.6]
        [chartjs/bar
         {:data {:labels labels
                 :datasets (->> datasets (mapv #(merge % {:borderWidth 0})))}
          :options (charts/wrap-default-options
                    {:scales
                     {:xAxes [{:stacked true, :ticks font, :scaleLabel font
                               :gridLines {:color (charts/graph-border-color)}}]
                      :yAxes [{:ticks font, :scaleLabel font
                               :gridLines {:color (charts/graph-border-color)}}]}
                     :legend {:labels font}})
          :height (* 2 150)}]]])))

(defn PredictionHistogram []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/prediction-histograms project-id]] {}
      [PredictionHistogramChart])))

(defn ProjectOverviewContent []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project project-id]
                  [:project/markdown-description project-id {:panel panel}]] {}
      [:div.overview-content
       [ProjectDescription {:panel panel}]
       [:div.ui.two.column.stackable.grid.project-overview
        [:div.column
         [ReviewStatusBox]
         [RecentProgressChart]
         [LabelPredictionsInfo]
         [PredictionHistogram]]
        [:div.column
         [MemberActivityChart]
         [ProjectFilesBox]
         [KeyTerms]
         [LabelCounts]]]])))

(defmethod panel-content [:project :project :overview] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      (if (false? @(subscribe [:project/has-articles?]))
        (do (nav/nav-redirect (project-uri project-id "/add-articles")
                              :scroll-top? true)
            [:div])
        [:div.project-content
         [ProjectOverviewContent]
         child]))))
