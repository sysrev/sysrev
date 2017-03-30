(ns sysrev.ui.sysrev
  (:require [sysrev.base :refer [st work-state]]
            [sysrev.ajax :refer [send-file-url pull-files delete-file get-file get-file-url]]
            [sysrev.state.core :as st :refer [data]]
            [sysrev.state.project :as project
             :refer [project project-admin?]]
            [sysrev.state.labels :as labels]
            [sysrev.util :refer
             [nav number-to-word full-size?]]
            [sysrev.shared.util :refer [in? num-to-english]]
            [sysrev.ui.components :refer [true-false-nil-tag]]
            [sysrev.ui.labels :refer [labels-page]]
            [sysrev.ui.upload :refer [upload-container basic-text-button]]
            [reagent.core :as r]
            [sysrev.shared.predictions :as predictions]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [sysrev.ui.charts :refer [chart-container line-chart bar-chart]]
            [goog.string :as string]
            [cljs-time.core :as t]
            [cljs-time.format :as tf])
  (:require-macros
   [sysrev.macros :refer [with-mount-hook using-work-state]]))

(defn user-info-card [user-id]
  (let [{:keys [email name]} (data [:users user-id])
        display-name (or name email)
        {:keys [articles in-progress]} (project :members user-id)
        num-include (-> articles :includes count)
        num-exclude (-> articles :excludes count)
        num-classified (+ num-include num-exclude)]
    [:div
     {:style {:margin-top "10px"
              :margin-bottom "0px"}}
     [:div.ui.top.attached.header.segment
      {:style {:padding-left "0.7em"
               :padding-right "0.7em"}}
      [:a {:href (str "/user/" user-id)}
       [:h4 display-name]]]
     [:table.ui.bottom.attached.unstackable.table
      {:style {:margin-left "-1px"}}
      [:thead
       [:tr
        [:th "Confirmed"]
        [:th "Included"]
        [:th "Excluded"]
        [:th "In progress"]]]
      [:tbody
       [:tr
        [:td [:span.attention (str num-classified)]]
        [:td [:span.attention (str num-include)]]
        [:td [:span.attention (str num-exclude)]]
        [:td [:span.attention (str in-progress)]]]]]]))

(defn selected-label-id []
  (or (st :page :project :active-label-id)
      (project :overall-label-id)))

(defn project-summary-box []
  (let [stats (project :stats)]
    [:div.ui.grey.two.column.celled.grid.segment.project-stats
     [:div.row.top-row
      [:div.ui.column.top-left
       [:span.attention (str (-> stats :articles))]
       " total articles"]
      [:div.ui.column.top-right
       [:span.attention
        (str (-> stats :labels :any))]
       " total reviewed"]]
     [:div.row
      [:div.ui.column
       [:span.attention
        (str (- (-> stats :labels :any)
                (-> stats :labels :single)))]
       " reviewed twice"]
      [:div.ui.column
       [:span.attention
        (str (-> stats :conflicts :resolved))]
       " resolved conflicts"]]
     [:div.row
      [:div.ui.column.bottom-left
       [:span.attention
        (str (-> stats :conflicts :pending))]
       " awaiting extra review"]
      [:div.ui.column.bottom-right]]]))

(defn label-counts-box []
  (let [stats (project :stats)]
    [:table.ui.celled.unstackable.table.grey.segment
     [:thead
      [:tr
       [:th "Criteria label counts"]
       [:th (true-false-nil-tag
             "large" nil true "Include" true false)]
       [:th (true-false-nil-tag
             "large" nil true "Exclude" false false)]
       #_
       [:th (true-false-nil-tag
             "large" nil false "Unknown" nil false)]]]
     [:tbody
      (doall
       (for [{:keys [label-id short-label category]}
             (labels/project-labels-ordered)]
         (when (= category "inclusion criteria")
           (let [counts (get-in stats [:inclusion-values label-id])]
             ^{:key {:label-stats label-id}}
             [:tr
              [:td short-label]
              [:td (str (get counts :true))]
              [:td (str (get counts :false))]
              #_ [:td (str (get counts :nil))]]))))]]))

(defn member-list-box []
  (let [user-ids (project/project-member-user-ids false)]
    [:div.ui.grey.segment
     [:h4 {:style {:margin-bottom "0px"}}
      "Project members"]
     (doall
      (->> user-ids
           (map
            (fn [user-id]
              ^{:key {:user-info user-id}}
              [user-info-card user-id]))))]))


(defn show-email [email] (-> email (clojure.string/split "@") first))
(defn user-summary-chart []
  (let [user-ids (project/project-member-user-ids false)
        users (mapv #(data [:users %]) user-ids)
        user-articles (->> user-ids (mapv #(->> % (project :members) :articles)))
        xs (mapv (fn [{:keys [email name]}] (or name (show-email email))) users)
        includes (mapv #(-> % :includes count) user-articles)
        excludes (mapv #(-> % :excludes count) user-articles)
        yss [includes excludes]
        ynames ["include" "exclude"]]
    [:div.ui.grey.segment
     [:h4.ui.dividing.header
      "Member activity"]
     [chart-container bar-chart xs ynames yss]]))

(defn project-page-menu-full [active-tab]
  (let [make-class #(if (= % active-tab) "active item" "item")
        has-predict-data (not-empty (project :stats :predict :confidences))
        make-tab (fn [tab path label]
                   ^{:key {:menu-tab tab}}
                   [:a {:class (make-class tab) :href path}
                    [:h4.ui.header label]])
        n-tabs (+ 4
                  (if has-predict-data 1 0)
                  #_ (if (project-admin?) 1 0))]
    [:div.ui
     {:class
      (str (num-to-english n-tabs)
           " item secondary pointing menu project-menu")}
     [make-tab :overview "/project" "Overview"]
     [make-tab :user-profile "/user" "User profile"]
     [make-tab :labels "/project/labels" "Labels"]
     (when has-predict-data
       [make-tab :predict "/project/predict" "Prediction"])
     #_
     (when (project-admin?)
       [make-tab :settings "/project/settings" "Settings"])
     [:a
      {:class (make-class :classify) :href "/project/classify"}
      [:div.ui.large.basic.button.classify "Classify"]]]))

(defn project-page-menu-mobile [active-tab]
  (let [make-class #(if (= % active-tab) "active item" "item")
        has-predict-data (not-empty (project :stats :predict :confidences))
        make-tab (fn [tab path label]
                   ^{:key {:menu-tab tab}}
                   [:a {:class (make-class tab) :href path}
                    [:h4.ui.header label]])
        n-tabs (+ 4
                  #_ (if has-predict-data 1 0)
                  #_ (if (project-admin?) 1 0))]
    [:div.ui
     {:class
      (str (num-to-english n-tabs)
           " item secondary pointing menu project-menu")}
     [make-tab :overview "/project" "Overview"]
     [make-tab :user-profile "/user" "User profile"]
     [make-tab :labels "/project/labels" "Labels"]
     #_
     (when has-predict-data
       [make-tab :predict "/project/predict" "Prediction"])
     #_
     (when (project-admin?)
       [make-tab :settings "/project/settings" "Settings"])
     [:a
      {:class (make-class :classify) :href "/project/classify"}
      [:div.ui.basic.button.classify "Classify"]]]))

(defn project-page-menu [active-tab]
  (if (full-size?)
    [project-page-menu-full active-tab]
    [project-page-menu-mobile active-tab]))


(def file-types {"doc" "word"
                 "docx" "word"
                 "pdf" "pdf"
                 "xlsx" "excel"
                 "xls" "excel"
                 "gz" "archive"
                 "tgz" "archive"
                 "zip" "archive"})

(defn get-file-class [fname]
  (get file-types (-> fname (.split ".") last) "text"))


(defonce editing-files (r/atom false))
(defn toggle-editing [] (swap! editing-files not))

(defn project-files-box []
  (letfn [(show-date [file]
            (let [date (tf/parse (:upload-time file))
                  parts (mapv #(% date) [t/month t/day t/year])]
              (apply goog.string/format "%d/%d/%d" parts)))]
    [:div.ui.grey.segment
     [:h4.item {:style {:margin-bottom "0px"}}
      "Project resources"]
     (when-let [files (:files (project))]
       [:div.ui.celled.list
        (doall
          (->>
            files
            (map
              (fn [file]
                [:div.icon.item {:key (:file-id file)}
                 (if @editing-files
                   [:i.ui.small.middle.aligned.red.delete.icon {:on-click #(delete-file (:file-id file))}]
                   [:i.ui.outline.blue.file.icon {:class (get-file-class (:name file))}])
                 [:div.content {:on-click #(get-file (:file-id file) (:name file))}
                  [:a.header {:href (get-file-url (:file-id file) (:name file))} (:name file)]
                  [:div.description (show-date file)]]]))))])
     [:div.ui.container
      [upload-container basic-text-button send-file-url pull-files "Upload Project Document"]
      [:div.ui.right.floated.basic.icon.button {:on-click toggle-editing :class (when @editing-files "red")}
       [:i.ui.small.small.blue.pencil.icon]]]]))

(defn project-overview-box []
  [:div.ui.two.column.stackable.grid
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     [label-counts-box]
     [project-files-box]]
    [:div.ui.column
     [user-summary-chart]
     [member-list-box]]]])

(defn train-input-summary-box []
  (let [label-id (selected-label-id)
        short-label (project :labels label-id :short-label)
        counts (project :stats :predict label-id :counts)]
    [:table.ui.celled.unstackable.table.grey.segment.mobile-table
     [:thead
      [:tr
       [:th "All articles"]
       [:th "Unlabeled articles"]
       [:th "Labeled " [:code "true"]]
       [:th "Labeled " [:code "false"]]]]
     [:tbody
      [:tr
       [:td (:total counts)]
       [:td (:unlabeled counts)]
       [:td (:include-label counts)]
       [:td (:exclude-label counts)]]]]))

(defn value-confidence-box []
  (let [label-id (selected-label-id)
        c-include (project :stats :predict label-id :include :confidence)
        c-exclude (project :stats :predict label-id :exclude :confidence)
        c-percents (-> c-include keys sort)]
    [:div.ui.grid
     [:div.ui.sixteen.wide.column
      [:div.ui.top.attached.grey.segment
       [:h4 "Article count by label probability"]]
      [:table.ui.celled.unstackable.bottom.attached.table.mobile-table
       [:thead
        [:tr
         [:th]
         (doall
          (for [percent c-percents]
            ^{:key {:confidence-header [label-id percent]}}
            [:th (str ">= " percent "%")]))]]
       [:tbody
        [:tr
         [:td
          [:code (if (full-size?) "Pr(true)" "true")]]
         (doall
          (for [percent c-percents]
            (let [n-articles (get c-include percent)]
              ^{:key {:confidence-count [:include label-id percent]}}
              [:td (str n-articles)])))]
        [:tr
         [:td
          [:code (if (full-size?) "Pr(false)" "false")]]
         (doall
          (for [percent c-percents]
            (let [n-articles (get c-exclude percent)]
              ^{:key {:confidence-count [:exclude label-id percent]}}
              [:td (str n-articles)])))]]]]]))

(defn predict-report-labels-menu []
  (let [predict-ids (keys (project :stats :predict))
        label-ids (->> (labels/project-labels-ordered)
                       (map :label-id)
                       (filter (in? predict-ids)))
        ncols (-> label-ids count number-to-word)
        active-label-id (selected-label-id)]
    [:div.ui.top.attached.disabled.segment
     [:h4
      "Select label: "
      (let [dropdown
            (with-mount-hook
              #(.dropdown (js/$ (r/dom-node %))))]
        [dropdown
         [:div.ui.small.blue.dropdown.button
          {:style {:margin-left "5px"}}
          [:input {:type "hidden" :name "menu-dropdown"}]
          [:label (project :labels active-label-id :short-label)]
          [:i.chevron.down.right.icon]
          [:div.menu
           (doall
            (for [label-id label-ids]
              (let [active (= label-id active-label-id)]
                ^{:key {:predict-menu-label label-id}}
                [:a.item
                 {:href (str "/project/predict/" (name label-id))
                  :class (if active "default active" "")}
                 (project :labels label-id :short-label)])))]]])]]))


(defn project-predict-report-table [vs]
  (letfn [(coverage [v] (/ (predictions/examples v) (:global-population-size v)))
          (mk-coverage-string [v]
            (string/format "%.1f%% (%d/%d)"
                           (* 100 (coverage v))
                           (predictions/examples v)
                           (:global-population-size v)))
          (conf-row [thresh]
            (let [v (conf-at-least thresh)]
              [:tr
               [:td "> " (-> thresh (* 100) (str "%"))]
               [:td
                (->> v predictions/true-positive-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-positives v) (predictions/condition-positives v))]
               [:td (->> v predictions/true-negative-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-negatives v) (predictions/condition-negatives v))]
               [:td (mk-coverage-string v)]]))
          (conf-row [thresh]
            (let [v (conf-at-least thresh)]
              [:tr
               [:td "> " (-> thresh (* 100) (str "%"))]
               [:td
                (->> v predictions/true-positive-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-positives v) (predictions/condition-positives v))]
               [:td (->> v predictions/true-negative-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-negatives v) (predictions/condition-negatives v))]
               [:td (mk-coverage-string v)]]))
          (conf-at-least [thresh] (->> vs (filterv #(> (:confidence %) thresh)) first :values))]
    [:table.ui.celled.table
     [:thead
      [:tr
       [:th "Confidence"]
       [:th "True Positive Rate (Sensitivity)"]
       [:th "True Negative Rate (Specificity)"]
       [:th "Coverage"]]]
     [:tbody
      [conf-row 0.9]
      [conf-row 0.95]
      [conf-row 0.99]]]))


(defn project-predict-report-box []
  (when-let [vs (project :stats :predict :confidences)]
    ;; Facilitate creation of multiple datasets, each as functions on the vector of confidence values.
    (letfn [(confs-by-f [& fs]
              (let [ffs (into [:confidence] (mapv #(comp % :values) fs))]
                (->> vs
                     (mapv (apply juxt ffs))
                     (filterv (comp (partial > 1.0) first)))))
            (coverage [v] (/ (predictions/examples v) (:global-population-size v)))]
      (let [data (confs-by-f predictions/positive-predictive-value predictions/negative-predictive-value coverage)
            confidences (->> data (map first) (map #(string/format "%.2f" %)))
            ppvs (mapv second data)
            npvs (mapv #(get % 2) data)
            coverages (mapv #(get % 3) data)]
        [:div
         [:div.ui.bottom.attached.segment
          [:div.ui.center.aligned.header "Prediction confidence report"]
          [:div.ui.segment
           [project-predict-report-table vs]]
          [:div.ui.segment
           [chart-container
            line-chart
            confidences
            ["Positive Predictive Value" "Negative Predictive Value" "Coverage"]
            [ppvs npvs coverages]]]]]))))

(defn project-invite-link-segment []
  [:div.ui.bottom.attached.segment.invite-link
   [:div.ui.fluid.labeled.input
    [:div.ui.label "Project invite URL"]
    [:input.ui.input
     {:readOnly true
      :value (project/project-invite-url (st/current-project-id))}]]])

(defn project-setting-wrapper [name description content]
  [:div.ui.middle.aligned.grid
   [:div.ui.row
    [:span.name [:span.inner name]]]
   [:div.ui.row
    [:span.description [:span.inner description]]]
   [:div.ui.row [:div.inner content]]])

(defn project-settings-page []
  (using-work-state
   (when (and (empty? (st :page :settings :active-values))
              (not-empty (project :settings)))
     (swap! work-state assoc-in
            [:page :settings :active-values]
            (project :settings))))
  (let [active (st :page :settings :active-values)
        saved (project :settings)
        row-size (if (full-size?) 2 1)
        row-size-word (if (full-size?) "two" "one")]
    [:div
     {:class (str "ui "
                  (if (full-size?) "two" "one")
                  " column celled grid segment")}
     (let [columns
           [[:div.ui.column
             [project-setting-wrapper
              "Double-review priority"
              "This controls classify task frequency of assigning an article for review by a second user. Setting to 100% will assign for second review always when any single-reviewed articles are available. Setting to 0% will assign unreviewed articles always when any are available."
              [:div]]]]]
       (doall
        (for [row (partition-all row-size columns)]
          [:div.ui.row (doall row)])))]))

(defn project-page [active-tab content]
  (let [bottom-segment?
        (case active-tab
          :overview false
          true)]
    [:div
     [:div.ui.top.attached.center.aligned.segment.project-header
      [:h5 (data [:all-projects (st/current-project-id) :name])]]
     [:div.ui.segment.project-segment
      {:class (if bottom-segment? "bottom attached" "attached")}
      [project-page-menu active-tab]
      [:div.padded content]]
     (when (= active-tab :overview)
       [project-invite-link-segment])]))
