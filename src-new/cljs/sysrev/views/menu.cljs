(ns sysrev.views.menu
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch]]
            [sysrev.util :refer [full-size?]]
            [sysrev.views.components :refer [dropdown-menu]])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn loading-indicator []
  (let [;; ready? @(subscribe [:data/ready?])
        loading? @(subscribe [:any-loading?])
        action? @(subscribe [:action/any-running?])]
    (when (or loading? action? #_ (not ready?))
      [:div.ui.small.active.inline.loader])))

(defn header-menu []
  (let [logged-in? @(subscribe [:self/logged-in?])
        user-id @(subscribe [:self/user-id])
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
      (when admin?
        [dropdown-menu [{:content "Clear query cache"
                         :action #(dispatch [:action [:clear-query-cache]])}]
         :dropdown-class "dropdown item"
         :label [:i.fitted.code.icon]])
      [:div.item.loading-indicator
       [loading-indicator]]
      (if logged-in?
        [:div.right.menu
         [dropdown-menu [{:content "Account settings"
                          :action "/user/settings"}
                         {:content "Log out"
                          :action #(dispatch [:action [:log-out]])}]
          :dropdown-class "dropdown item"
          :style {:padding-left "2.5em"
                  :padding-right "2.5em"}
          :label [:span.blue-text
                  [:i.user.icon] user-display]]
         [:div.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:href "/login"}
          "Log in"]
         [:div.item {:style {:width "0" :padding "0"}}]])]]))
