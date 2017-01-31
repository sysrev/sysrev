(ns sysrev.ui.user-profile
  (:require [sysrev.base :refer [st work-state]]
            [sysrev.state.core :as s :refer [data]]
            [sysrev.state.project :as project :refer [project]]
            [sysrev.state.labels :as labels]
            [sysrev.ui.components :refer [debug-box]]
            [sysrev.ui.article :refer [article-short-info-component]]
            [sysrev.ajax :as ajax]
            [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [with-mount-hook using-work-state]]))

(defn user-profile-page []
  (let [user-id (or (st :page :user-profile :user-id)
                    (s/current-user-id))
        project-id (s/current-project-id)
        user (st :data :users user-id)
        display-name (or (:name user) (:email user))
        unconfirmed-ids (keys (project :member-labels user-id :unconfirmed))
        confirmed-ids (keys (project :member-labels user-id :confirmed))
        active-tab
        (as-> (st :page :user-profile :articles-tab)
            active-tab
          (if (= active-tab :default)
            (cond (not= 0 (count unconfirmed-ids)) :unconfirmed
                  :else :confirmed)
            active-tab))]
    [:div.sixteen.wide.column
     [:div.ui.segments
      [:div.ui.top.attached.header.segment.user-menu
       (if (nil? project-id)
         [:h3 display-name]
         (let [dropdown
               (with-mount-hook
                 #(.dropdown (js/$ (r/dom-node %))
                             (clj->js {})))]
           [dropdown
            [:div.ui.fluid.dropdown.button.user-menu
             [:input {:type "hidden" :name "menu-dropdown"}]
             [:label (data [:users user-id :email])
              [:i.chevron.down.icon]]
             [:div.menu
              (doall
               (for [link-user-id (project/project-member-user-ids true)]
                 (let [active (= link-user-id user-id)]
                   ^{:key {:user-menu-entry link-user-id}}
                   [:a.item
                    {:href (str "/user/" link-user-id)
                     :class (if active "default active" "")}
                    (data [:users link-user-id :email])])))]]]))]
      (when (and (= user-id (s/current-user-id))
                 (s/admin-user? user-id))
        [:div.ui.attached.segment.user-admin-segment
         [:div.ui.two.column.grid
          [:div.ui.column
           [:a.ui.fluid.button
            {:on-click ajax/post-delete-user}
            "Delete account"]]
          [:div.ui.column
           [:a.ui.fluid.button
            {:on-click ajax/post-delete-member-labels}
            "Delete user labels"]]]])
      [:div.ui.bottom.attached.segment
       [:div.ui.secondary.pointing.large.two.item.menu
        [:a.item
         {:class (if (= active-tab :unconfirmed) "active" "")
          :on-click
          #(using-work-state
            (swap! work-state assoc-in
                   [:page :user-profile :articles-tab] :unconfirmed))}
         (str (count unconfirmed-ids)
              " article"
              (if (= 1 (count unconfirmed-ids)) "" "s")
              " in progress")]
        [:a.item
         {:class (if (= active-tab :confirmed) "active" "")
          :on-click
          #(using-work-state
            (swap! work-state assoc-in
                   [:page :user-profile :articles-tab] :confirmed))}
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
