(ns sysrev-web.ui.sysrev
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ui.users :as users]))

(defn project-summary-box []
  (let [stats (-> @server-data :sysrev :stats)]
    [:div.ui.raised.segments
     [:h2.ui.top.attached.header.center.aligned
      "Project summary"]
     [:div.ui.bottom.attached.segment
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
         " awaiting extra review"]]]]]))

(defn label-stats-box []
  (let [stats (-> @server-data :sysrev :stats)]
    [:div.ui.raised.segments
     [:h2.ui.top.attached.header.center.aligned
      "Label statistics"]
     [:div.ui.bottom.attached.segment
      (doall
       (for [cid (-> stats :label-values keys)]
         (let [clabel (get-in @server-data [:criteria cid :short_label])
               counts (get-in stats [:label-values cid])]
           ^{:key {:label-stats cid}}
           [:div.ui.vertical.segment
            [:div.ui.four.column.grid.label-stats
             [:div.ui.row
              [:div.ui.column
               (str clabel "?")]
              [:div.ui.column
               [:span.attention
                (str (:true counts))]
               " true"]
              [:div.ui.column
               [:span.attention
                (str (:false counts))]
               " false"]
              [:div.ui.column
               [:span.attention
                (str (:unknown counts))]
               " unknown"]]]])))]]))

(defn user-list-box []
  (let [user-ids (-> @server-data :sysrev :users keys)]
    [:div.ui.raised.segments
     [:h2.ui.top.attached.header.center.aligned
      "Members"]
     [:div.ui.attached.segment.cards
      (doall
       (->> user-ids
            (map
             (fn [user-id]
               ^{:key {:user-info user-id}}
               [users/user-info-card user-id]))))]]))

(defn project-page []
  [:div.ui.container
   [:div.ui.two.column.grid
    [:div.ui.row
     [:div.ui.column
      [project-summary-box]
      [label-stats-box]]
     [:div.ui.column
      [user-list-box]]]]])
