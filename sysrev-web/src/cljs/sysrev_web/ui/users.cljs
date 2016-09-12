(ns sysrev-web.ui.users
  (:require [sysrev-web.base :refer [server-data]]))

(defn user-info-card [user-id]
  (let [users (-> @server-data :sysrev :users)
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
     [:div.ui.row.attached.segment
      [:div.ui.six.wide.column
       [:span.attention
        (str num-classified)]
       " articles classified"]
      [:div.ui.five.wide.column
       [:span.attention
        (str num-include)]
       " included"]
      [:div.ui.five.wide.column
       [:span.attention
        (str num-exclude)]
       " excluded"]]
     [:div.ui.row.bottom.attached.segment
      [:div.ui.five.wide.column
       [:span.attention
        (str num-in-progress)]
       " in progress"]]]))

(defn users-page []
  (let [users (-> @server-data :sysrev :users)
        user-ids (keys users)]
    [:div.ui.container
     [:h1 "User Activity Summary"]
     [:div.ui.cards
      (->> user-ids
           (map-indexed
            (fn [idx user-id]
              ^{:key {:user-summary user-id}}
              [user-info-card user-id])))]]))
