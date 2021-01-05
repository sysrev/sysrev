(ns sysrev.views.panels.project.overview
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [sysrev.chartjs :as chartjs]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.charts :as charts]
            [sysrev.views.panels.project.articles :as articles]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.views.semantic :refer [Button]]
            [sysrev.util :as util :refer [css wrap-user-event]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :overview]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})

(defn- unpad-chart [unpad-em content]
  (let [margin-top (if (sequential? unpad-em) (first unpad-em) unpad-em)
        margin-bottom (if (sequential? unpad-em) (second unpad-em) unpad-em)
        to-css #(str "-" % "em")]
    [:div {:style {:margin-top (to-css margin-top)
                   :margin-bottom (to-css margin-bottom)}}
     content]))

(defn- nav-article-status [[inclusion group-status]]
  (when @(subscribe [:active-project-id])
    (articles/load-consensus-settings :status group-status
                                      :inclusion inclusion)))

(defn- LabelStatusHelpColumn [colors]
  (let [scounts @(subscribe [:project/status-counts])
        scount #(get scounts % 0)
        color-border #(str "1px solid " (get colors %))
        wrap-nav #(wrap-user-event % :prevent-default true)]
    [:div.label-status-help
     [:div.ui.segments
      [:div.ui.attached.segment.status-header.green
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
      [:div.ui.attached.segment.status-header.orange
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

(def-data :project/review-status
  :uri      "/api/review-status"
  :loaded?  (fn [db project-id]
              (-> (get-in db [:data :project project-id :stats])
                  (contains? :status-counts)))
  :content  (fn [project-id] {:project-id project-id})
  :process  (fn [{:keys [db]} [project-id] result]
              {:db (assoc-in db [:data :project project-id :stats :status-counts] result)})
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:db (assoc-in db [:data :project project-id :stats :status-counts] {:error error})}))

(defn- ReviewStatusBox []
  (let [project-id @(subscribe [:active-project-id])
        {:keys [reviewed total]} @(subscribe [:project/article-counts])
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
        item [:project/review-status project-id]
        have? @(subscribe [:have? item])
        loading? (data/loading? item)]
    (when-not unlimited-reviews
      [:div.project-summary
       [:div.ui.segment
        [:div.ui.inverted.dimmer {:class (css [loading? "active"])}
         [:div.ui.loader]]
        [:h4.ui.header {:class (css [have? "dividing"])} "Review Status"]
        (with-loader [item] {}
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
              [LabelStatusHelpColumn colors]]]]])]])))

(defn txt->emails [txt]
  (when (string? txt)
    (->> (str/split txt #"[ ,\n]")
         (map str/trim)
         (filter util/email?))))

(def-action :project/send-invites
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id emails-txt]
             (let [emails (txt->emails emails-txt)]
               {:project-id project-id
                :emails emails}))
  :process (fn [_ [_] {:keys [success message]}]
             (when success
               {:dispatch-n [[::set [:invite-emails :emails-txt] ""]
                             [:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(defn- InviteEmailsCmp []
  (let [project-id @(subscribe [:active-project-id])
        emails-txt (r/cursor state [:invite-emails :emails-txt])]
    (fn []
      (let [emails (txt->emails @emails-txt)
            email-count (count emails)
            unique-count (count (set emails))
            running? (action/running? :project/send-invites)]
        [:form.ui.form.bulk-invites-form
         {:on-submit (util/wrap-prevent-default
                       #(dispatch [:action [:project/send-invites project-id @emails-txt]]))}
         [:div.field
          [:textarea#bulk-invite-emails
           {:style {:width "100%"}
            :value @emails-txt
            :required true
            :placeholder "Input a list of emails separated by comma, newlines or spaces."
            :on-change (util/wrap-prevent-default
                         #(reset! emails-txt (-> % .-target .-value)))}]]
         [Button {:primary true
                  :id "send-bulk-invites-button"
                  :disabled (or running? (zero? unique-count))
                  :type "submit"}
          "Send Invites"]
         (when (> email-count 0)
           [:span {:style {:margin-left "10px"}}
            (case email-count
              1 "1 email recognized"
              (str email-count " emails recognized"))
            (when (> email-count unique-count)
              (str " (" unique-count " unique)"))])]))))

(defn- MemberActivityChart []
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
                                    @(subscribe [:user/dev?])))]
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
         [ui/ClipboardButton "#invite-url" "Copy Invite Link"]])
      
      (when invite?
        [:h4.ui.dividing.header {:style {:margin-top "1.5em"}}
         "Send invitation emails"])
      (when invite?
        [InviteEmailsCmp])]]))

(defn- RecentProgressChart []
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
              :scales {:x {:ticks (->> {:autoSkip true
                                        :callback (fn [value idx values]
                                                    (if (or (= 0 (mod idx 5))
                                                            (= idx (dec (count values))))
                                                      value ""))}
                                       (merge font))
                           :gridLines {:color (charts/graph-border-color)}
                           :scaleLabel font}
                       :y {:ticks font
                           :suggestedMin (max 0
                                              (int (- (first xvals)
                                                      (* xdiff 0.15))))
                           :suggestedMax (min n-total
                                              (int (+ (last xvals)
                                                      (* xdiff 0.15))))
                           :gridLines {:color (charts/graph-border-color)}
                           :scaleLabel (->> {:display true
                                             :labelString "User Articles Labeled"
                                             :fontSize 14}
                                            (merge font))}}})]
        [:div.ui.segment
         [:h4.ui.dividing.header "Recent Progress"]
         (with-loader [[:project project-id]] {:dimmer :fixed}
           [:div {:style {:padding-top "0.5em"
                          :margin-bottom "-0.6em"}}
            [chartjs/line {:data data :options options :height 275}]])]))))

(defn- LabelPredictionsInfo []
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

(def-data :project/important-terms-text
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :important-terms-text)))
  :uri (fn [_] "/api/important-terms-text")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] result]
             {:db (assoc-in db [:data :project project-id :important-terms-text] result)})
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:db (assoc-in db [:data :project project-id :important-terms-text] {:error error})}))

(reg-sub :project/important-terms-text
         (fn [[_ _ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:important-terms-text project)))

(defn- ImportantTermsChart [{:keys [data]}]
  (when (not-empty data)
    (let [height (* 2 (+ 8 (* 10 (count data))))
          entries (->> data (sort-by :tfidf >))
          labels (->> entries (mapv :term))
          scores (->> entries (mapv :tfidf) (mapv #(/ % 10000.0)))]
      [:div [charts/bar-chart height labels ["Relevance"] [scores]
             :colors ["rgba(33,186,69,0.55)"]
             :options {:legend {:display false}}
             :display-ticks false
             :log-scale true]])))

(defn- KeyTerms []
  (let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/important-terms-text project-id]] {}
      (let [terms-res @(subscribe [:project/important-terms-text])
            {:keys [terms]} terms-res]
        (when (not-empty terms)
          [:div.ui.segment
           [:h4.ui.dividing.header "Important Terms"]
           [:div
            [unpad-chart [0.25 0.35]
             [ImportantTermsChart {:data terms}]]]])))))

(reg-sub :project/label-counts
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:label-counts project)))

(def-data :project/label-counts
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :label-counts)))
  :uri (fn [] "/api/charts/label-count-data")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] {:keys [data]}]
             {:db (assoc-in db [:data :project project-id :label-counts]
                            data)}))

(defn- LabelCountChart []
  (let [color-filter (r/atom #{})]
    (fn []
      (when-let [label-counts (not-empty @(subscribe [:project/label-counts]))]
        (let [label-ids @(subscribe [:project/label-ids])
              font (charts/graph-font-settings)
              filtered-color? #(contains? @color-filter %)
              color-filter-fn #(remove (comp filtered-color? :color) %)
              entries (->> (color-filter-fn label-counts)
                           (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids))
                                      (:label-id %))))
              max-length (if (util/mobile?) 22 28)
              labels (->> entries
                          (mapv :value)
                          (mapv str)
                          (mapv #(if (<= (count %) max-length)
                                   % (str (subs % 0 (- max-length 2)) "..."))))
              counts (mapv :count entries)
              background-colors (mapv :color entries)
              legend-labels
              (->> (map (fn [[_ v]] (first v)) (group-by :label-id label-counts))
                   (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids))
                              (:label-id %)))
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
                                                   background-colors)
                                :maxBarThickness 12}]}
              options (charts/wrap-default-options
                       {:scales
                        {:x {:scaleLabel (->> {:display true
                                               :labelString "User Answers"}
                                              (merge font))
                             ;; :type "logarithmic"
                             :stacked false
                             :suggestedMin 0
                             :ticks (->> {:callback (fn [value idx values]
                                                      (if (or (= 0 (mod idx 5))
                                                              (= idx (dec (count values))))
                                                        value ""))}
                                         (merge font))
                             :gridLines {:color (charts/graph-border-color)}}
                         ;; this is actually controlling the labels
                         :y {:scaleLabel font
                             :ticks (->> {:padding 7} (merge font))
                             :gridLines {:drawTicks false
                                         :color (charts/graph-border-color)}}}
                        :legend
                        {:labels (->> {:generateLabels (fn [_] (clj->js legend-labels))}
                                      (merge font))
                         :onClick
                         (fn [_e legend-item]
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
                        (fn [_e elts]
                          (when-let [idx (and (pos-int? (.-length elts))
                                              (-> elts (aget 0) .-index))]
                            (let [{:keys [label-id value]} (nth entries idx)]
                              (articles/load-label-value-settings label-id value))))}
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

(defn- LabelCounts []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/label-counts project-id]] {}
      [LabelCountChart])))

(reg-sub ::prediction-histograms
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:histograms project)))

(def-data :project/prediction-histograms
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :histograms)))
  :uri (fn [] "/api/prediction-histograms")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] {:keys [prediction-histograms]}]
             {:db (assoc-in db [:data :project project-id :histograms]
                            prediction-histograms)}))

(defn- PredictionHistogramChart []
  (let [font (charts/graph-font-settings)
        prediction-histograms @(subscribe [::prediction-histograms])
        chart-labels (->> (vals prediction-histograms)
                          flatten (map :score) distinct sort vec)
        histograms-data (fn [k]
                          (let [histogram (get prediction-histograms k)
                                score->count (zipmap (mapv :score histogram)
                                                     (mapv :count histogram))]
                            (mapv #(or (get score->count %) 0)
                                  chart-labels)))
        include-data    (histograms-data :reviewed-include-histogram)
        exclude-data    (histograms-data :reviewed-exclude-histogram)
        unreviewed-data (histograms-data :unreviewed-histogram)
        datasets        (cond-> []
                          (some pos? include-data)
                          (conj {:label "Reviewed - Include"
                                 :data include-data
                                 :backgroundColor (:green colors)})
                          (some pos? exclude-data)
                          (conj {:label "Reviewed - Exclude"
                                 :data exclude-data
                                 :backgroundColor (:red colors)})
                          (some pos? unreviewed-data)
                          (conj {:label "Unreviewed"
                                 :data unreviewed-data
                                 :backgroundColor (:orange colors)}))]
    (when (seq datasets)
      [:div.ui.segment
       [:h4.ui.dividing.header "Prediction Histograms"]
       [unpad-chart [0.5 0.6]
        [chartjs/bar
         {:data {:labels chart-labels
                 :datasets (->> datasets (mapv #(merge % {:borderWidth 0})))}
          :options (charts/wrap-default-options
                    {:scales (util/map-values
                              #(merge % {:ticks font
                                         :scaleLabel font
                                         :gridLines {:color (charts/graph-border-color)}})
                              {:x {:stacked true}
                               :y {}})
                     :legend {:labels font}})
          :height (* 2 150)}]]])))

(defn- PredictionHistogram []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/prediction-histograms project-id]] {}
      [PredictionHistogramChart])))

(defn- ProjectOverviewContent []
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
         [PredictionHistogram]
         [KeyTerms]]
        [:div.column
         [MemberActivityChart]
         [ProjectFilesBox]
         [LabelCounts]]]])))

(defn- Panel [child]
  (when-let [project-id @(subscribe [:active-project-id])]
    (if (false? @(subscribe [:project/has-articles?]))
      [:div (nav/nav (project-uri project-id "/add-articles") :redirect true)]
      [:div.project-content
       [ProjectOverviewContent]
       child])))

(def-panel :project? true :panel panel
  :uri "" :params [project-id] :name project
  :on-route (let [prev-panel @(subscribe [:active-panel])
                  all-items [[:project project-id]
                             [:project/review-status project-id]
                             [:project/markdown-description project-id {:panel panel}]
                             [:project/label-counts project-id]
                             [:project/important-terms-text project-id]
                             [:project/prediction-histograms project-id]]]
              ;; avoid reloading data on project url redirect
              (when (and prev-panel (not= panel prev-panel))
                (doseq [item all-items] (dispatch [:reload item])))
              (when (not= panel prev-panel)
                ;; slight delay to reduce intermediate rendering during data load
                (js/setTimeout #(dispatch [:set-active-panel panel]) 20)))
  :content (fn [child] [Panel child]))
