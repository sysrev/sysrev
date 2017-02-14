(ns sysrev.ui.sysrev
  (:require [sysrev.base :refer [st]]
            [sysrev.state.core :as s :refer [data]]
            [sysrev.state.project :as project :refer [project]]
            [sysrev.state.labels :as labels]
            [sysrev.util :refer
             [nav number-to-word full-size? in?]]
            [sysrev.ui.components :refer [true-false-nil-tag]]
            [sysrev.ui.labels :refer [labels-page]]
            [reagent.core :as r]
            [sysrev.shared.predictions :as predictions]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [sysrev.ui.charts :refer [chart-container line-chart]]
            [goog.string :as string])

  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

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

(defn project-page-menu-full [active-tab]
  (let [make-class
        #(if (= % active-tab) "active item" "item")]
    [:div.ui.five.item.secondary.pointing.menu.project-menu
     [:a
      {:class (make-class :overview)
       :href "/project"}
      [:h4.ui.header "Overview"]]
     [:a
      {:class (make-class :user-profile)
       :href "/user"}
      [:h4.ui.header "User profile"]]
     [:a
      {:class (make-class :labels)
       :href "/project/labels"}
      [:h4.ui.header "Labels"]]
     [:a
      {:class (make-class :predict)
       :href "/project/predict"}
      [:h4.ui.header "Prediction"]]
     [:a
      {:class (make-class :classify)
       :href "/project/classify"}
      [:div.ui.large.basic.button.classify
       "Classify"]]]))

(defn project-page-menu-mobile [active-tab]
  (let [make-class
        #(if (= % active-tab) "active item" "item")]
    [:div.ui.four.item.secondary.pointing.menu.project-menu
     [:a
      {:class (make-class :overview)
       :href "/project"}
      [:h4.ui.header "Overview"]]
     [:a
      {:class (make-class :user-profile)
       :href "/user"}
      [:h4.ui.header "User"]]
     [:a
      {:class (make-class :labels)
       :href "/project/labels"}
      [:h4.ui.header "Labels"]]
     [:a
      {:class (make-class :classify)
       :href "/project/classify"}
      [:div.ui.basic.button.classify
       "Classify"]]]))

(defn project-page-menu [active-tab]
  (if (full-size?)
    [project-page-menu-full active-tab]
    [project-page-menu-mobile active-tab]))

(defn project-overview-box []
  [:div.ui.two.column.stackable.grid
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     [label-counts-box]]
    [:div.ui.column
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


(defn project-predict-report-box []
  (let [vs (project :stats :predict :confidences)
        ;; Facilitate creation of multiple datasets, each as functions on the vector of confidence values.
        confs-by-f (fn [& fs]
                     (let [ffs (into [:confidence] (mapv #(comp % :values) fs))]
                      (->> vs
                        (mapv (apply juxt ffs))
                        (filterv (comp (fn [x] (< x 1.0)) first)))))
        coverage (fn [v] (/ (predictions/examples v) (:global-population-size v)))
        data (confs-by-f predictions/positive-predictive-value coverage)
        confidences (->> data (map first) (map #(string/format "%.2f" %)))
        ppvs (map second data)
        coverages (map (comp second rest) data)
        conf-at-least (fn [thresh] (->> vs (filterv #(> (:confidence %) thresh)) first :values))
        mk-coverage-string (fn [v]
                             (string/format "%.1f%% (%d/%d)"
                                             (* 100 (coverage v))
                                             (predictions/examples v)
                                             (:global-population-size v)))
        conf-row (fn [thresh]
                   (let [v (conf-at-least thresh)]
                     [:tr
                      [:td (-> thresh (* 100) (str "%"))]
                      [:td (->> v predictions/balanced-accuracy (* 100) (string/format "%.1f%%"))]
                      [:td (mk-coverage-string v)]]))]
    [:div
     [:div.ui.bottom.attached.segment
      [:div.ui.center.aligned.header "Confidence report"]
      [:div.ui.segment
       [:table.ui.celled.table
        [:thead
         [:tr
          [:th "Confidence"]
          [:th "Balanced Accuracy"]
          [:th "Coverage"]]]
        [:tbody
         [conf-row 0.9]
         [conf-row 0.95]
         [conf-row 0.99]]]]
      [:div.ui.segment
       [chart-container
        (line-chart
          confidences
          ["Positive predictive value" "Coverage"]
          [ppvs coverages])]]]]))


(defn project-invite-link-segment []
  [:div.ui.bottom.attached.segment.invite-link
   [:div.ui.fluid.labeled.input
    [:div.ui.label "Project invite URL"]
    [:input.ui.input
     {:readOnly true
      :value (project/project-invite-url (s/current-project-id))}]]])

(defn project-page [active-tab content]
  (let [bottom-segment?
        (case active-tab
          :overview false
          true)]
    [:div
     [:div.ui.top.attached.center.aligned.segment.project-header
      [:h5 (data [:all-projects (s/current-project-id) :name])]]
     [:div.ui.segment.project-segment
      {:class (if bottom-segment? "bottom attached" "attached")}
      [project-page-menu active-tab]
      [:div.padded content]]
     (when (= active-tab :overview)
       [project-invite-link-segment])]))
