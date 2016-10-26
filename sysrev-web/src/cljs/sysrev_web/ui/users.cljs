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
    [:div
     {:style {:margin-bottom "14px"}}
     [:div.ui.top.attached.header.segment
      {:style {:padding-left "0.7em"
               :padding-right "0.7em"}}
      [:a {:href (str "/user/" user-id)}
       [:h4 display-name]]]
     [:table.ui.bottom.attached.unstackable.table
      {:style {:margin-left "-1px"}}
      [:thead
       [:tr
        [:th "Confirmed"]
        [:th "Included"]
        [:th "Excluded"]
        [:th "In progress"]]]
      [:tbody
       [:tr
        [:td [:span.attention (str num-classified)]]
        [:td [:span.attention (str num-include)]]
        [:td [:span.attention (str num-exclude)]]
        [:td [:span.attention (str num-in-progress)]]]]]]))
