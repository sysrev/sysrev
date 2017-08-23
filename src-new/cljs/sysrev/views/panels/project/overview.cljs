(ns sysrev.views.panels.project.overview
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [sysrev.views.article-list :refer [group-statuses]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.charts :refer [chart-container pie-chart]]
   [sysrev.views.panels.project.public-labels :as public-labels]
   [sysrev.routes :refer [nav-scroll-top]]
   [sysrev.util :refer [full-size?]]
   [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn nav-group-status [status]
  (when (in? group-statuses status)
    (nav-scroll-top "/project/articles")
    (dispatch [:public-labels/reset-filters [:group-status]])
    (dispatch [:public-labels/set-group-status status])))

(defn- chart-value-labels [entries]
  [:div.ui.one.column.center.aligned.middle.aligned.grid
   (->>
    (partition-all 4 entries)
    (map-indexed
     (fn [i row]
       [:div.ui.middle.aligned.column
        {:key (str i)
         :style {:padding-right "2em"
                 :padding-left "0.5em"}}
        [:div.ui.middle-aligned.list
         (->>
          row
          (map-indexed
           (fn [i [value color label status]]
             [:div.item
              {:key (str i)}
              [:div.ui.fluid.basic.button
               {:style {:padding "7px"
                        :margin "4px"
                        :border (str "1px solid " color)}
                :on-click #(nav-group-status status)}
               [:span (str "View " label " (" value ")")]]]))
          doall)]]))
    doall)])

(defn- project-summary-box []
  (let [{:keys [reviewed unreviewed total]}
        @(subscribe [:project/article-counts])
        {:keys [single consistent conflict resolved]}
        @(subscribe [:project/labeled-counts])
        grey "rgba(160,160,160,0.5)"
        green "rgba(20,200,20,0.5)"
        red "rgba(220,30,30,0.5)"
        blue "rgba(30,100,230,0.5)"
        purple "rgba(146,29,252,0.5)"
        statuses [:single :consistent :conflict :resolved]]
    [:div.project-summary
     [:div.ui.grey.segment
      [:h4.ui.center.aligned.dividing.header
       (str reviewed " articles reviewed of " total " total")]
      (with-loader [[:project]] {:dimmer true}
        [:div.ui.two.column.stackable.middle.aligned.grid.pie-charts
         [:div.row
          [:div.column
           [chart-container pie-chart
            [["Single" single blue]
             ["Consistent" consistent green]
             ["Conflicting" conflict red]
             ["Resolved" resolved purple]]
            #(nav-group-status
              (nth [:single :consistent :conflict :resolved] %))]]
          [:div.column.pie-chart
           [chart-value-labels
            [[consistent green "consistent" :consistent]
             [conflict red "conflicting" :conflict]
             [resolved purple "resolved" :resolved]]]]]])]]))

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
  [:div.ui.two.column.stackable.grid.project-overview
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     #_ [project-files-box]]
    #_ [:div.ui.column
        [user-summary-chart]]]])

(defmethod panel-content [:project :project :overview] []
  (fn [child]
    [:div.project-content
     [project-overview-panel]
     child]))
