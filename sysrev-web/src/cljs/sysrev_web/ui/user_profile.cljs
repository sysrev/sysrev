(ns sysrev-web.ui.user-profile
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.state.data :as d]
            [sysrev-web.ui.components :refer [debug-box]]
            [sysrev-web.ui.article :refer [article-short-info-component]]))

(defn user-profile-page []
  (let [user-id (get-in @state [:page :user-profile :user-id])
        user (get-in @state [:data :users user-id])
        display-name (or (:name user) (:email user))
        unconfirmed-ids (-> (d/member-labels user-id)
                            :unconfirmed keys)
        confirmed-ids (->> (d/member-labels user-id)
                           :confirmed keys)
        active-tab
        (as-> (get-in @state [:page :user-profile :articles-tab])
            active-tab
          (if (= active-tab :default)
            (cond (not= 0 (count unconfirmed-ids)) :unconfirmed
                  :else :confirmed)
            active-tab))]
    [:div.sixteen.wide.column
     [:div.ui.segments
      [:div.ui.top.attached.header.segment
       [:h3 display-name]]
      [:div.ui.bottom.attached.segment
       [:div.ui.secondary.pointing.large.two.item.menu
        [:a.item
         {:class (if (= active-tab :unconfirmed) "active" "")
          :on-click #(swap! state assoc-in [:page :user-profile :articles-tab]
                            :unconfirmed)}
         (str (count unconfirmed-ids)
              " article"
              (if (= 1 (count unconfirmed-ids)) "" "s")
              " in progress")]
        [:a.item
         {:class (if (= active-tab :confirmed) "active" "")
          :on-click #(swap! state assoc-in [:page :user-profile :articles-tab]
                            :confirmed)}
         (str (count confirmed-ids) " confirmed article"
              (if (= 1 (count confirmed-ids)) "" "s"))]]
       (let [article-ids (case active-tab
                           :unconfirmed unconfirmed-ids
                           :confirmed confirmed-ids
                           nil)]
         (doall
          (for [article-id (take 25 article-ids)]
            ^{:key {:user-article {:a article-id :u user-id}}}
            [article-short-info-component article-id true user-id])))]]]))
