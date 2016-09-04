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
        num-classified (+ num-include num-exclude)]
    [:div.ui.fluid.card
     [:div.content
      [:div.header
       [:a.ui.link {:href (str "/user/" user-id)} display-name]]]
     [:div.content (str num-classified " articles classified")]
     [:div.content.list
      [:div.item (str num-include " articles included")]
      [:div.item (str num-exclude " articles excluded")]]]))

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
