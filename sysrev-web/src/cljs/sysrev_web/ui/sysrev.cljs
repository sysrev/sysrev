(ns sysrev-web.ui.sysrev
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ui.users :as users]))

(defn project-page []
  (let [stats (-> @server-data :sysrev :stats)
        user-ids (-> @server-data :sysrev :users keys)]
    [:div.ui.container
     [:div.ui.two.column.grid
      [:div.ui.row
       [:div.ui.column
        [:div.ui.raised.segments
         ;; {:style {:width "75%" :margin-left "auto" :margin-right "auto"}}
         [:h2.ui.top.attached.gray.header.center.aligned
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
             " awaiting extra review"]]]]]]
       [:div.ui.column
        [:div.ui.raised.segments
         ;; {:style {:width "75%" :margin-left "auto" :margin-right "auto"}}
         [:h2.ui.top.attached.gray.header.center.aligned
          "Members"]
         [:div.ui.attached.segment.cards
          (doall
           (->> user-ids
                (map
                 (fn [user-id]
                   ^{:key {:user-info user-id}}
                   [users/user-info-card user-id]))))]]]]]]))
