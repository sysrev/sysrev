(ns sysrev-web.ui.sysrev
  (:require [sysrev-web.base :refer [state build-profile]]
            [sysrev-web.state.data :refer [data]]
            [sysrev-web.util :refer
             [nav number-to-word full-size?]]
            [sysrev-web.ui.users :as users]
            [reagent.core :as r])
  (:require-macros [sysrev-web.macros :refer [with-mount-hook]]))

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
        clabel (data [:criteria cid :short-label])]
    [:table.ui.celled.unstackable.table.grey.raised.segment
     [:thead
      [:tr
       [:th "Label counts"]
       [:th [:code "true"]]
       [:th [:code "false"]]
       [:th [:code "unknown"]]]]
     [:tbody
      (doall
       (for [cid (-> stats :label-values keys)]
         (let [clabel (get-in @state [:data :criteria cid :short-label])
               counts (get-in stats [:label-values cid])]
           ^{:key {:label-stats cid}}
           [:tr
            [:td clabel]
            [:td (str (:true counts))]
            [:td (str (:false counts))]
            [:td (str (:unknown counts))]])))]]))

(defn user-list-box []
  (let [users (-> @state :data :sysrev :users)
        user-ids
        (->> (keys users)
             (filter
              (if (= build-profile :dev)
                (fn [_] true)
                (fn [user-id]
                  (not
                   (some (partial = "admin")
                         (-> (get users user-id)
                             :user
                             :site-permissions)))))))]
    [:div.ui.raised.grey.segment
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
  [:div.ui.two.column.stackable.grid
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     [label-counts-box]]
    [:div.ui.column
     [user-list-box]]]])

(defn train-input-summary-box []
  (let [cid (selected-criteria-id)
        clabel (data [:criteria cid :short-label])
        counts (data [:sysrev :stats :predict cid :counts])]
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
  (let [cid (selected-criteria-id)
        c-include (data [:sysrev :stats :predict cid :include :confidence])
        c-exclude (data [:sysrev :stats :predict cid :exclude :confidence])
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
            ^{:key {:confidence-header [cid percent]}}
            [:th (str ">= " percent "%")]))]]
       [:tbody
        [:tr
         [:td
          [:code (if (full-size?) "Pr(true)" "true")]]
         (doall
          (for [percent c-percents]
            (let [n-articles (get c-include percent)]
              ^{:key {:confidence-count [:include cid percent]}}
              [:td (str n-articles)])))]
        [:tr
         [:td
          [:code (if (full-size?) "Pr(false)" "false")]]
         (doall
          (for [percent c-percents]
            (let [n-articles (get c-exclude percent)]
              ^{:key {:confidence-count [:exclude cid percent]}}
              [:td (str n-articles)])))]]]]]))

(defn predict-report-criteria-menu []
  (let [cids (keys (data [:sysrev :stats :predict]))
        ncols (-> cids count number-to-word)
        active-cid (selected-criteria-id)]
    [:div.ui.top.attached.segment
     [:h4
      "Select label: "
      (let [dropdown
            (with-mount-hook
              #(.dropdown (js/$ (r/dom-node %)))
              [:div.ui.small.blue.dropdown.button
               {:style {:margin-left "5px"}}
               [:input {:type "hidden" :name "menu-dropdown"}]
               [:label (data [:criteria active-cid :short-label])]
               [:i.chevron.down.right.icon]
               [:div.menu
                (doall
                 (for [cid cids]
                   (let [active (= cid active-cid)]
                     ^{:key {:predict-menu-cid cid}}
                     [:a.item
                      {:href (str "/project/predict/" cid)
                       :class (if active "default active" "")}
                      (data [:criteria cid :short-label])])))]])]
        [dropdown])]]))

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
  [:div
   [project-page-menu]
   [:div.ui.bottom.attached.segment
    (case (-> @state :page :project :tab)
      :overview [project-overview-box]
      :predict [project-predict-report-box]
      [:div "Sub-page not found"])]])
