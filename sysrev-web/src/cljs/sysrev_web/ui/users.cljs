(ns sysrev-web.ui.users
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.state.data :as d]))

(defn user-info-card [user-id]
  (let [{:keys [email name]} (d/data [:users user-id])
        display-name (or name email)
        {:keys [articles in-progress]} (get (d/project :members) user-id)
        num-include (-> articles :includes count)
        num-exclude (-> articles :excludes count)
        num-classified (+ num-include num-exclude)]
    [:div
     {:style {:margin-top "10px"
              :margin-bottom "0px"}}
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
        [:td [:span.attention (str in-progress)]]]]]]))
