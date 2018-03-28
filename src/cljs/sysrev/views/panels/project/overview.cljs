(ns sysrev.views.panels.project.overview
  (:require
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-event-fx reg-sub trim-v]]
   [re-frame.db :refer [app-db]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.views.article-list :refer [group-statuses]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :refer [with-ui-help-tooltip]]
   [sysrev.views.charts :refer [chart-container pie-chart bar-chart
                                get-canvas-context wrap-animate-options
                                paul-tol-colors Chart]]
   [sysrev.views.panels.project.public-labels :as public-labels]
   [sysrev.views.upload :refer [upload-container basic-text-button]]
   [sysrev.routes :as routes]
   [sysrev.subs.project :refer [active-project-id]]
   [sysrev.util :refer [full-size? random-id continuous-update-until]]
   [cljs-time.core :as t]
   [cljs-time.coerce :refer [from-date]]
   [sysrev.shared.util :refer [in?]]
   [clojure.string :as str])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def initial-state {})
(def state (r/cursor app-db [:state :panels :project :overview]))

(reg-event-fx
 :overview/reset-state!
 [trim-v]
 (fn [_]
   (reset! state initial-state)
   {}))

(defn nav-article-status [[inclusion group-status]]
  (routes/nav-scroll-top "/project/articles")
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
     [:div.ui.segment
      [:h4.ui.dividing.header
       "Review Status"]
      (with-loader [[:project]] {:dimmer :fixed
                                 :require false}
        [:div
         [:h4.ui.center.aligned.header
          (str reviewed " articles reviewed of " total " total")]
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
    [:div.ui.segment
     [:h4.ui.dividing.header
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        "Member Activity"]]]
     (with-loader [[:project]] {:dimmer :fixed
                                :require false}
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
    [:div.ui.segment
     [:h4.ui.dividing.header
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        "Recent Progress"]]]
     (with-loader [[:project]] {:dimmer :fixed
                                :require false}
       [chart-container
        make-chart nil
        (->> progress (mapv :completed)
             #_ (mapv #(* 100.0 (/ % n-total))))
        (->> progress (mapv :day)
             (mapv #(->> (str/split % #"\-") (drop 1) (str/join "-"))))])]))

(defn label-predictions-box []
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

(defonce polling-important-terms? (r/atom false))

(defn poll-important-terms []
  (when (not @polling-important-terms?)
    (reset! polling-important-terms? true)
    (dispatch [:fetch [:project/important-terms]])
    (let [server-loading? (subscribe [:project/important-terms-loading?])
          ajax-loading? (subscribe [:loading? [:project/important-terms]])
          updating? (fn [] (or @server-loading? @ajax-loading?))]
      (continuous-update-until #(dispatch [:fetch [:project/important-terms]])
                               #(not (updating?))
                               #(reset! polling-important-terms? false)
                               1000))))

(defn ImportantTermsChart [{:keys [entity data loading?]} title]
  (when (not-empty data)
    (let [height (str (+ 35 (* 10 (count data))) "px")]
      [:div.ui.segment
       [:div
        [:h4.ui.dividing.header
         [:div.ui.two.column.middle.aligned.grid
          [:div.ui.left.aligned.column
           title]]]
        (with-loader
          [[:project] [:project/important-terms]]
          {:require false
           :dimmer :fixed
           :force-dimmer loading?}
          (let [entries (->> data (sort-by :instance-count >))
                labels (mapv :instance-name entries)
                counts (mapv :instance-count entries)]
            [chart-container
             bar-chart height
             labels ["count"] [counts]
             ["rgba(33,186,69,0.55)"]
             {:legend {:display false}}]))]])))

(defn KeyTerms []
  (let [terms @(subscribe [:project/important-terms])
        loading? @(subscribe [:project/important-terms-loading?])
        {:keys [mesh chemical gene]} terms]
    (with-loader [[:project]]
      (when loading?
        (poll-important-terms))
      [:div
       [ImportantTermsChart
        {:entity :mesh, :data mesh, :loading? loading?}
        "Important Mesh Terms"]
       [ImportantTermsChart
        {:entity :chemical, :data chemical, :loading? loading?}
        "Important Compounds"]
       [ImportantTermsChart
        {:entity :gene, :data gene, :loading? loading?}
        "Important Genes"]])))

(defn label-answer-counts
  "Extract the answer counts for labels for the current project"
  [label-definitions public-labels]
  (let [raw-labels-with-values (->> public-labels
                                    (mapv #(hash-map :labels (:labels %) :article-id (:article-id %))))
        label-uuid->short-label (fn [label-uuid]
                                  (:short-label (label-definitions label-uuid)))
        label-uuid->value-type (fn [label-uuid]
                                 (:value-type (label-definitions label-uuid)))
        extract-labels-fn (fn [raw-label]
                            (->> raw-label
                                 ((fn [article] (map #(map (partial merge
                                                                    {:short-label (label-uuid->short-label (first %))
                                                                     :article-id (:article-id article)
                                                                     :value-type (label-uuid->value-type (first %))
                                                                     :label-id (first %)})
                                                           (second %))
                                                     (:labels article))))
                                 flatten))
        label-values (flatten (map extract-labels-fn raw-labels-with-values))
        label-counts-fn (fn [label]
                          (hash-map :short-label (first label)
                                    :answer-counts (frequencies (map :answer (second label)))
                                    :value-type (:value-type (first (second label)))
                                    :label-id (:label-id (first (second label)))))]
    (map label-counts-fn (group-by :short-label label-values))))

(defn process-label-count
  "Given a coll of public-labels, return a vector of value-count maps"
  [label-definitions public-labels]
  (->> public-labels
       ((partial label-answer-counts label-definitions))
       (map (fn [label-count]
              (map #(hash-map :short-label
                              (:short-label label-count)
                              :value-type (:value-type label-count)
                              :value (let [putative-value (first %)]
                                       (if (vector? putative-value)
                                         (clojure.string/join "," putative-value)
                                         putative-value))
                              :count (second %)
                              :label-id (:label-id label-count))
                   (:answer-counts label-count))))
       flatten
       (into [])))

(defn short-labels-vector
  "Given a set of label-counts, get the set of short-labels"
  [processed-label-counts]
  ((comp (partial into []) sort set (partial mapv :short-label))
   processed-label-counts))

(defn processed-label-color-map
  "Given a set of label-counts, generate a color map"
  [processed-label-counts]
  (let [short-labels (short-labels-vector processed-label-counts)
        palette (nth paul-tol-colors (- (count short-labels) 1))
        color-map (zipmap short-labels palette)]
    (mapv (fn [label palette]
            {:short-label label :color palette})
          short-labels palette)))

(defn add-color-processed-label-counts
  "Given a processed-label-count, add color to each label"
  [processed-label-counts]
  (let [color-map (processed-label-color-map processed-label-counts)]
    (mapv #(merge % {:color (:color (first (filter (fn [m]
                                                     (= (:short-label %)
                                                        (:short-label m))) color-map)))})
          processed-label-counts)))

(defn LabelCountChart
  []
  (let [label-definitions (get-in @app-db [:data :project (active-project-id @app-db) :labels])]
    (fn [public-labels]
      (when (not (empty? public-labels))
        (let [label-ids @(subscribe [:project/label-ids])
              processed-label-counts (->> public-labels
                                          ;; get the counts of the label's values
                                          ((partial process-label-count label-definitions))
                                          ;; filter out labels of type string
                                          (filterv #(not= (:value-type %)
                                                          "string"))
                                          ;; add color
                                          add-color-processed-label-counts
                                          ;; do initial sort by values
                                          (sort-by #(str (:value %)))
                                          ;; sort booleans such that true goes before false
                                          ;; sort categorical alphabetically
                                          ((fn [processed-public-labels]
                                             (let [grouped-processed-public-labels (group-by :value-type processed-public-labels)
                                                   boolean-labels (get grouped-processed-public-labels "boolean")
                                                   categorical-labels (get grouped-processed-public-labels "categorical")]
                                               (concat (reverse (sort-by :value boolean-labels))
                                                       (sort-by :value categorical-labels)))))
                                          ;; https://clojuredocs.org/clojure.core/sort-by#example-542692cbc026201cdc326c2f
                                          ;; use the order of the labels as they appear in review articles (label-ids)
                                          (sort-by
                                           #((into {} (map-indexed (fn [i e] [e i]) label-ids)) (:label-id %))))
              labels (mapv :value processed-label-counts)
              counts (mapv :count processed-label-counts)
              background-colors (mapv :color processed-label-counts)
              color-map (processed-label-color-map processed-label-counts)
              short-label->label-uuid (fn [short-label]
                                        (:label-id (first (filter #(= short-label
                                                                      (:short-label %))
                                                                  (vals label-definitions)))))
              legend-labels (->> color-map
                                 (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids)) (short-label->label-uuid (:short-label %))))
                                 (mapv #(hash-map :text (:short-label %) :fillStyle (:color %))))]
          [Chart {:type "horizontalBar"
                  :data {:labels labels
                         :datasets [{:data counts
                                     :backgroundColor background-colors}]}
                  :options {:scales
                            {:xAxes
                             [{:display true
                               :ticks {:suggestedMin 0
                                       :callback (fn [value idx values]
                                                   (if (or (= 0 (mod idx 5))
                                                           (= idx (dec (count values))))
                                                     value ""))}}]
                             :yAxes
                             [{:maxBarThickness 10}]}
                            :legend {:labels
                                     {:generateLabels (fn [chart]
                                                        (clj->js legend-labels))}}
                            }}
           "Label Counts"])))))

(defn LabelCounts
  []
  (let [public-labels (r/cursor app-db [:data :project (active-project-id @app-db) :public-labels])]
    (r/create-class
     {:reagent-render
      (fn []
        [LabelCountChart @public-labels])
      :component-did-mount
      (fn [this] (dispatch [:fetch [:project/public-labels]]))})))

(defn project-overview-panel []
  (let []
    [:div.ui.two.column.stackable.grid.project-overview
     [:div.ui.row
      [:div.ui.column
       [project-summary-box]
       [recent-progress-chart]
       [label-predictions-box]]
      [:div.ui.column
       [user-summary-chart]
       [project-files-box]
       [KeyTerms]
       [LabelCounts]]]]))

(defmethod panel-content [:project :project :overview] []
  (fn [child]
    (let [has-articles? @(subscribe [:project/has-articles?])]
      [:div.project-content
       (if (false? has-articles?)
         (do (routes/nav-scroll-top "/project/add-articles")
             [:div])
         [project-overview-panel])
       child])))
