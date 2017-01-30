(ns sysrev.ui.sysrev
  (:require [sysrev.base :refer [state]]
            [sysrev.state.core :as s]
            [sysrev.state.data :as d]
            [sysrev.util :refer
             [nav number-to-word full-size? in?]]
            [sysrev.ui.users :as users]
            [sysrev.ui.components :refer [true-false-nil-tag]]
            [sysrev.ui.labels :refer [labels-page]]
            [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn selected-label-id []
  (or (-> @state :page :project :active-label-id)
      (d/project :overall-label-id)))

(defn project-summary-box []
  (let [stats (d/project :stats)]
    [:div.ui.grey.segment
     [:div.ui.three.column.grid.project-stats
      [:div.ui.row
       [:div.ui.column
        [:span.attention
         (str (-> stats :articles))]
        " total articles"]
       [:div.ui.column
        [:span.attention
         (str (-> stats :labels :any))]
        " total reviewed"]
       [:div.ui.column
        [:span.attention
         (str (- (-> stats :labels :any)
                 (-> stats :labels :single)))]
        " reviewed twice"]]]
     [:div.ui.two.column.grid.project-stats
      [:div.ui.row
       {:style {:padding-top "0px"}}
       [:div.ui.column
        [:span.attention
         (str (-> stats :conflicts :resolved))]
        " resolved conflicts"]
       [:div.ui.column
        [:span.attention
         (str (-> stats :conflicts :pending))]
        " awaiting extra review"]]]]))

(defn label-counts-box []
  (let [stats (d/project :stats)]
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
             (d/project-labels-ordered)]
         (when (= category "inclusion criteria")
           (let [counts (get-in stats [:inclusion-values label-id])]
             ^{:key {:label-stats label-id}}
             [:tr
              [:td short-label]
              [:td (str (get counts :true))]
              [:td (str (get counts :false))]
              #_ [:td (str (get counts :nil))]]))))]]))

(defn member-list-box []
  (let [user-ids (d/project-member-user-ids false)]
    [:div.ui.grey.segment
     [:h4 {:style {:margin-bottom "0px"}}
      "Project members"]
     (doall
      (->> user-ids
           (map
            (fn [user-id]
              ^{:key {:user-info user-id}}
              [users/user-info-card user-id]))))]))

(defn project-page-menu [active-tab]
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
       "Classify articles"]]]))

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
        short-label (d/project [:labels label-id :short-label])
        counts (d/project [:stats :predict label-id :counts])]
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
        c-include (d/project [:stats :predict label-id :include :confidence])
        c-exclude (d/project [:stats :predict label-id :exclude :confidence])
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
  (let [predict-ids (keys (d/project [:stats :predict]))
        label-ids (->> (d/project-labels-ordered)
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
          [:label (d/project [:labels active-label-id :short-label])]
          [:i.chevron.down.right.icon]
          [:div.menu
           (doall
            (for [label-id label-ids]
              (let [active (= label-id active-label-id)]
                ^{:key {:predict-menu-label label-id}}
                [:a.item
                 {:href (str "/project/predict/" (name label-id))
                  :class (if active "default active" "")}
                 (d/project [:labels label-id :short-label])])))]]])]]))

(defn project-predict-report-box []
  (let [label-id (selected-label-id)]
    [:div
     [:div.ui.secondary.yellow.center.aligned.segment
      [:h3 "Under development"]]
     [predict-report-labels-menu]
     [:div.ui.bottom.attached.disabled.segment
      [train-input-summary-box]
      [value-confidence-box]]
     #_
     [:div.ui.secondary.segment
      [:h4 (str "Last updated: " (d/project [:stats :predict label-id :update-time]))]]]))

(defn project-invite-link-segment []
  [:div.ui.bottom.attached.segment.invite-link
   [:div.ui.fluid.labeled.input
    [:div.ui.label "Project invite URL"]
    [:input.ui.input {:readOnly true
                      :value (d/project-invite-url
                              (s/active-project-id))}]]])

(defn project-page [active-tab content]
  (let [bottom-segment?
        (case active-tab
          :overview false
          true)]
    [:div
     [:div.ui.top.attached.center.aligned.segment.project-header
      [:h5 (d/data [:all-projects (s/active-project-id) :name])]]
     [:div.ui.segment.project-segment
      {:class (if bottom-segment? "bottom attached" "attached")}
      [project-page-menu active-tab]
      [:div.padded content]]
     (when (= active-tab :overview)
       [project-invite-link-segment])]))
