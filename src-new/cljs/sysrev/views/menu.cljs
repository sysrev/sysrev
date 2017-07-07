(ns sysrev.views.menu
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch]]
            [sysrev.util :refer [full-size?]])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn loading-indicator []
  (let [ready? @(subscribe [:data/ready?])
        loading? @(subscribe [:any-loading?])]
    (when (or (not ready?) loading?)
      [:div.ui.small.active.inline.loader])))

(defn header-menu []
  (let [logged-in? @(subscribe [:logged-in?])
        user-id @(subscribe [:user-id])
        user-display @(subscribe [:user/display])
        admin? @(subscribe [:user/admin?])
        project-ids @(subscribe [:user/project-ids])
        full? (full-size?)]
    [:div.ui.top.menu.site-menu
     [:div.ui.container
      [:a.header.item
       {:href "/"}
       [:h3.ui.blue.header
        "sysrev.us"]]
      [:a.item.loading-indicator
       [loading-indicator]]
      (if logged-in?
        [:div.right.menu
         (when admin?
           [:a.item
            {:on-click #(dispatch [:clear-query-cache])}
            "Clear query cache"])
         [:a.item.blue-text {:href (str "/user/" user-id)}
          [:div
           [:i.blue.user.icon]
           user-display]]
         (when (or admin? (< 1 (count project-ids)))
           [:a.item {:href "/select-project"}
            "Change project"])
         [:a.item.distinct.middle.aligned
          {:on-click #(dispatch [:log-out])}
          "Log out"]
         [:a.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:href "/login"}
          "Log in"]])]]))
