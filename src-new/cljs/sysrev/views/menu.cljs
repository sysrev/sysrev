(ns sysrev.views.menu
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch]]
            [sysrev.util :refer [full-size?]]
            [sysrev.views.components :refer [dropdown-menu]])
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
      (when admin?
        [dropdown-menu [{:content "Clear query cache"}]
         :dropdown-class "dropdown item"
         :label [:i.fitted.code.icon]])
      [:a.item.loading-indicator
       [loading-indicator]]
      (if logged-in?
        [:div.right.menu
         [dropdown-menu [{:content "Project page"
                          :action "/project/user"}
                         {:content "Account settings"
                          :action "/user/settings"}]
          :dropdown-class "dropdown item"
          :label [:span.blue-text
                  [:i.user.icon] user-display]]
         [:a.item.middle.aligned
          {:on-click #(dispatch [:log-out])}
          "Log out"]
         [:a.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:href "/login"}
          "Log in"]])]]))
