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
       {:on-click #(dispatch [:navigate []])}
       [:h3.ui.blue.header
        "sysrev.us"]]
      [:a.item
       {:on-click #(dispatch [:navigate [:project]])}
       "Projects"]
      [:div.item.loading-indicator
       [loading-indicator]]
      (if logged-in?
        [:div.right.menu
         (when admin?
           [dropdown-menu [{:content "Clear query cache"
                            :action #(dispatch [:action [:dev/clear-query-cache]])}]
            :dropdown-class "dropdown item"
            :label [:i.fitted.code.icon]])
         [:a.item {:on-click #(dispatch [:navigate [:user-settings]])}
          "Settings"]
         [:a.item {:on-click #(dispatch [:action [:auth/log-out]])}
          "Log Out"]
         [:div.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:on-click #(dispatch [:navigate [:login]])}
          "Log in"]
         [:div.item {:style {:width "0" :padding "0"}}]])]]))
