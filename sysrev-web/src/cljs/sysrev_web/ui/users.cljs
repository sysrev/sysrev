(ns sysrev-web.ui.users
  (:require [sysrev-web.base :refer [server-data]]
            [sysrev-web.routes :as routes]
            [reagent.core :as r]))

(defn users-page []
  (let [users (-> @server-data :users)
        user-ids (keys users)]
    [:div.ui.container
     [:h1 "User Activity Summary"]
     [:div.ui.cards
      (->> user-ids
           (map-indexed
            (fn [idx user-id]
              (let [u (get users user-id)
                    email (-> u :user :email)
                    name (-> u :user :name)
                    display-name (or name email)
                    articles (-> u :articles)
                    num-include (-> u :articles :includes count)
                    num-exclude (-> u :articles :excludes count)
                    num-classified (+ num-include num-exclude)]
                ^{:key {:user-summary user-id}}
                [:div.ui.fluid.card
                 [:div.content
                  [:div.header
                   [:a.ui.link {:href (str "/user/" user-id)} display-name]]]
                 [:div.content (str num-classified " articles classified")]
                 [:div.content.list
                  [:div.item (str num-include " articles included")]
                  [:div.item (str num-exclude " articles excluded")]]]))))]]))
