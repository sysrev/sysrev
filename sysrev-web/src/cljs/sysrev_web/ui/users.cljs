(ns sysrev-web.ui.users
  (:require [sysrev-web.base :refer [state]]))

(defn user-info-card [user-id]
  (let [users (-> @state :data :sysrev :users)
        u (get users user-id)
        email (-> u :user :email)
        name (-> u :user :name)
        display-name (or name email)
        articles (-> u :articles)
        num-include (-> u :articles :includes count)
        num-exclude (-> u :articles :excludes count)
        num-classified (+ num-include num-exclude)
        num-in-progress (-> u :in-progress)]
    [:div.ui.grid.padded.user-card
     [:div.ui.row.top.attached.segment
      [:div.ui.six.wide.column
       [:a.ui.link {:href (str "/user/" user-id)}
        [:h4.header display-name]]]]
     [:div.ui.row.bottom.attached.segment.user-card-counts
      [:div.ui.four.wide.column.user-card-column
       [:span.attention
        (str num-classified)]
       " confirmed"]
      [:div.ui.four.wide.column.user-card-column
       [:span.attention
        (str num-include)]
       " included"]
      [:div.ui.four.wide.column.user-card-column
       [:span.attention
        (str num-exclude)]
       " excluded"]
      [:div.ui.four.wide.column.user-card-column
       [:span.attention
        (str num-in-progress)]
       " in progress"]]]))
