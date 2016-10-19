(ns sysrev-web.ui.sysrev
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.state.data :refer [data]]
            [sysrev-web.util :refer [nav number-to-word]]
            [sysrev-web.ui.users :as users]))

(defn selected-criteria-id []
  (or (-> @state :page :project :active-cid)
      (data [:overall-cid])))

(defn project-summary-box []
  (let [stats (-> @state :data :sysrev :stats)]
    [:div.ui.grey.raised.segment
     [:div.ui.three.column.grid.project-stats
      [:div.ui.row
       [:div.ui.column
        [:span.attention
         (str (-> stats :articles))]
        " total articles"]
       [:div.ui.column
        [:span.attention
         (str (- (-> stats :labels :any)
                 (-> stats :labels :single)))]
        " fully reviewed"]
       [:div.ui.column
        [:span.attention
         (str (-> stats :labels :single))]
        " reviewed once"]]]
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
  (let [stats (-> @state :data :sysrev :stats)
        cid (selected-criteria-id)
        clabel (data [:criteria cid :short_label])]
    [:div.ui.grey.raised.segment
     {:style {:padding "0px"}}
     [:table.ui.celled.table
      [:thead
       [:tr
        [:th "Label counts"]
        [:th [:code "true"]]
        [:th [:code "false"]]
        [:th [:code "unknown"]]]]
      [:tbody
       (doall
        (for [cid (-> stats :label-values keys)]
          (let [clabel (get-in @state [:data :criteria cid :short_label])
                counts (get-in stats [:label-values cid])]
            ^{:key {:label-stats cid}}
            [:tr
             [:td clabel]
             [:td (str (:true counts))]
             [:td (str (:false counts))]
             [:td (str (:unknown counts))]])))]]]))

(defn user-list-box []
  (let [user-ids (-> @state :data :sysrev :users keys)]
    [:div.ui.raised.grey.segment.cards
     [:h4 "Project members"]
     (doall
      (->> user-ids
           (map
            (fn [user-id]
              ^{:key {:user-info user-id}}
              [users/user-info-card user-id]))))]))

(defn project-page-menu []
  (let [make-class
        (fn [tab]
          (if (= tab (-> @state :page :project :tab))
            "active item" "item"))]
    [:div.ui.top.attached.two.item.tabular.menu
     [:a
      {:class (make-class :overview)
       :href "/project"}
      [:h3.ui.blue.header "Progress overview"]]
     [:a
      {:class (make-class :predict)
       :href "/project/predict"}
      [:h3.ui.blue.header "Prediction report"]]]))

(defn project-overview-box []
  [:div.ui.two.column.grid
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     [label-counts-box]]
    [:div.ui.column
     [user-list-box]]]])

(defn train-input-summary-box []
  (let [cid (selected-criteria-id)
        clabel (data [:criteria cid :short_label])
        counts (data [:sysrev :stats :predict cid :counts])]
    [:div.ui.grey.no-padding.segment
     [:table.ui.celled.table
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
        [:td (:exclude-label counts)]]]]]))

(defn value-confidence-box []
  (let [cid (selected-criteria-id)
        c-include (data [:sysrev :stats :predict cid :include :confidence])
        c-exclude (data [:sysrev :stats :predict cid :exclude :confidence])
        c-percents (-> c-include keys sort)]
    [:div.ui.grey.no-padding.segment
     [:table.ui.celled.table
      [:thead
       [:tr
        [:th "Article count by label probability"]
        (doall
         (for [percent c-percents]
           ^{:key {:confidence-header [cid percent]}}
           [:th (str ">= " percent "%")]))]]
      [:tbody
       [:tr
        [:td
         [:code "Pr(true)"]]
        (doall
         (for [percent c-percents]
           (let [n-articles (get c-include percent)]
             ^{:key {:confidence-count [:include cid percent]}}
             [:td (str n-articles)])))]
       [:tr
        [:td
         [:code "Pr(false)"]]
        (doall
         (for [percent c-percents]
           (let [n-articles (get c-exclude percent)]
             ^{:key {:confidence-count [:exclude cid percent]}}
             [:td (str n-articles)])))]]]]))

(defn predict-report-criteria-menu []
  (let [cids (keys (data [:sysrev :stats :predict]))
        ncols (-> cids count number-to-word)
        active-cid (selected-criteria-id)]
    [:div.ui.top.attached.segment
     [:div.ui.large.inverted.form
      [:div.inline.fields
       {:style {:margin-bottom "0px"}}
       (doall
        (for [cid cids]
          (let [active (= cid active-cid)]
            ^{:key {:predict-menu-cid cid}}
            [:div.field
             [:a.ui.large.button
              {:href (str "/project/predict/" cid)
               :class (if active "blue" "grey")}
              [:div.ui.radio.checkbox
               [:input {:type "radio"
                        :name "predict-criteria"
                        :checked (if active "checked" nil)
                        :on-change identity}]
               [:label (data [:criteria cid :short_label])]]]])))]]]))

(defn project-predict-report-box []
  (let [cid (selected-criteria-id)]
    [:div
     [predict-report-criteria-menu]
     [:div.ui.bottom.attached.segment
      [train-input-summary-box]
      [value-confidence-box]]
     [:div.ui.secondary.segment
      [:h4 (str "Last updated: " (data [:sysrev :stats :predict cid :update-time]))]]]))

(defn project-page []
  [:div.ui.container
   [:div.ui
    [project-page-menu]
    [:div.ui.bottom.attached.segment
     (case (-> @state :page :project :tab)
       :overview [project-overview-box]
       :predict [project-predict-report-box]
       [:div "Sub-page not found"])]]])
