(ns sysrev.views.panels.project.overview
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [labeled-input]]
   [sysrev.views.charts :refer [chart-container pie-chart]]
   [sysrev.views.panels.project.article-list :as article-list]
   [sysrev.routes :refer [nav nav-scroll-top]]
   [sysrev.util :refer [full-size? mobile?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn nav-article-status [status]
  (nav-scroll-top "/project/articles")
  (dispatch [::article-list/set-answer-status status])
  (dispatch [::article-list/reset-filters [:answer-status]]))

(defn- chart-value-labels [entries]
  [:div.ui.one.column.center.aligned.grid
   {:style {:padding "8px"}}
   (->>
    (partition-all 4 entries)
    (map-indexed
     (fn [i row]
       [:div.column {:key (str i)}
        (->>
         row
         (map-indexed
          (fn [i [value color]]
            [:div.ui.small.basic.button
             {:key (str i)
              :style {:padding "4px"
                      :margin "3px"
                      :border (str "1px solid " color)}}
             [:span (str value)]]))
         doall)]))
    doall)])

(defn project-summary-box []
  (let [{:keys [reviewed unreviewed]}
        @(subscribe [:project/article-counts])
        {:keys [single consistent conflict resolved]}
        @(subscribe [:project/labeled-counts])
        grey "rgba(160,160,160,0.5)"
        green "rgba(20,200,20,0.5)"
        red "rgba(220,30,30,0.5)"
        blue "rgba(30,100,230,0.5)"
        purple "rgba(146,29,252,0.5)"
        statuses [:single :consistent :conflict :resolved]
        on-click #(nav-article-status (nth statuses %))]
    [:div.project-summary
     [:div.ui.top.attached.grey.segment.header-with-buttons
      [:div.ui.two.column.middle.aligned.grid
       [:div.left.aligned.column
        [:h4.ui.header "Review status"]]
       [:div.right.aligned.column
        [:a.ui.tiny.button
         {:on-click #(nav-article-status :conflict)}
         "View conflicts"]]]]
     [:div.ui.bottom.attached.segment
      (with-loader [[:project]] {:dimmer true}
        [:div.ui.two.column.stackable.grid
         [:div.row
          [:div.column.pie-chart
           [chart-container pie-chart
            [["Single" single blue]
             ["Double" consistent green]
             ["Conflicting" conflict red]
             ["Resolved" resolved purple]]
            on-click]
           [chart-value-labels
            [[single blue]
             [consistent green]
             [conflict red]
             [resolved purple]]]]
          [:div.column.pie-chart]]])]]))

#_
(defn user-summary-chart []
  (let [user-ids @(subscribe [:project/member-user-ids])
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

(defn project-overview-panel []
  [:div.ui.two.column.stackable.grid
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     #_ [project-files-box]]
    [:div.ui.column
     #_ [user-summary-chart]]]])

(defmethod panel-content [:project :project :overview] []
  (fn [child]
    [:div
     [project-overview-panel]
     child]))
