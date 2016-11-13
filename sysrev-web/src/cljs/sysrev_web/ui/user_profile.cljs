(ns sysrev-web.ui.user-profile
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.state.data :as d]
            [sysrev-web.ui.components :refer [debug-box]]
            [sysrev-web.ui.article :refer [article-short-info-component]]))

(defn user-profile-page []
  (let [user-id (get-in @state [:page :user-profile :user-id])
        user (get-in @state [:data :users user-id])
        display-name (or (:username user) (:name user) (:email user))
        overall-cid (d/project :overall-cid)
        unconfirmed-ids (->> user :labels :unconfirmed keys)
        completed-ids
        (->> unconfirmed-ids
             (filter #((comp not nil?)
                       (get-in (d/user-label-values % user-id)
                               [overall-cid :answer]))))
        incomplete-ids
        (->> unconfirmed-ids
             (filter #(nil?
                       (get-in (d/user-label-values % user-id)
                               [overall-cid :answer]))))
        confirmed-ids (->> user :labels :confirmed keys)
        active-tab
        (as-> (get-in @state [:page :user-profile :articles-tab])
            active-tab
          (if (= active-tab :default)
            (cond (not= 0 (count completed-ids)) :completed
                  (not= 0 (count incomplete-ids)) :incomplete
                  :else :confirmed)
            active-tab))]
    [:div.sixteen.wide.column
     [:div.ui.segments
      [:div.ui.top.attached.header.segment
       [:h3 display-name]]
      [:div.ui.bottom.attached.segment
       [:div.ui.secondary.pointing.large.three.item.menu
        [:a.item
         {:class (if (= active-tab :completed) "active" "")
          :on-click #(swap! state assoc-in [:page :user-profile :articles-tab]
                            :completed)}
         (str (count completed-ids) " awaiting confirmation")]
        [:a.item
         {:class (if (= active-tab :incomplete) "active" "")
          :on-click #(swap! state assoc-in [:page :user-profile :articles-tab]
                            :incomplete)}
         (str (count incomplete-ids) " unfinished article"
              (if (= 1 (count incomplete-ids)) "" "s"))]
        [:a.item
         {:class (if (= active-tab :confirmed) "active" "")
          :on-click #(swap! state assoc-in [:page :user-profile :articles-tab]
                            :confirmed)}
         (str (count confirmed-ids) " confirmed article"
              (if (= 1 (count confirmed-ids)) "" "s"))]]
       (let [article-ids (case active-tab
                           :completed completed-ids
                           :incomplete incomplete-ids
                           :confirmed confirmed-ids
                           nil)]
         (doall
          (for [article-id (take 25 article-ids)]
            ^{:key {:user-article {:a article-id :u user-id}}}
            [article-short-info-component article-id true user-id])))]]]))
