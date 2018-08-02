(ns sysrev.views.menu
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch]]
            [sysrev.util :refer [full-size? mobile? nbsp]]
            [sysrev.views.components :refer [dropdown-menu with-tooltip]]
            [sysrev.nav :refer [nav nav-scroll-top]])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn loading-indicator []
  (let [project-id @(subscribe [:active-project-id])
        loading?
        (and @(subscribe [:any-loading? nil [:article/annotations]])
             (not (and project-id
                       @(subscribe
                         [:loading? [:project/sources project-id]])))
             (not (and project-id
                       @(subscribe
                         [:loading? [:project/important-terms project-id]]))))
        action? @(subscribe [:action/any-running? nil [:sources/delete]])]
    (if (or loading? action?)
      [:div.item.loading-indicator
       [:div.ui.small.active.inline.loader]]
      [:div.item.loading-indicator-disabled])))

(defn toggle-ui-theme [logged-in? settings]
  (let [new-theme (if (= (:ui-theme settings) "Dark")
                    "Default" "Dark")]
    (if logged-in?
      (dispatch [:action
                 [:user/change-settings
                  [{:setting :ui-theme, :value new-theme}]]])
      (dispatch [:action
                 [:session/change-settings
                  (merge settings {:ui-theme new-theme})]]))))

(defn header-menu []
  (let [logged-in? @(subscribe [:self/logged-in?])
        user-id @(subscribe [:self/user-id])
        user-display @(subscribe [:user/display])
        admin? @(subscribe [:user/admin?])
        project-ids @(subscribe [:user/project-ids])
        full? (full-size?)
        mobile? (mobile?)
        dev-menu (when admin?
                   [dropdown-menu [{:content "Clear query cache"
                                    :action #(dispatch [:action [:dev/clear-query-cache]])}]
                    :dropdown-class "dropdown item"
                    :label [:i.fitted.code.icon]])
        settings @(subscribe [:self/settings])
        toggle-theme-button
        (fn []
          (list
           ^{:key "tooltip-elt"}
           [with-tooltip
            [:a.item.toggle-theme
             {:on-click #(toggle-ui-theme logged-in? settings)}
             [:span {:style {:font-size "22px"}}
              [:i.fitted.lightbulb.outline.icon]]]
            {:delay {:show 600
                     :hide 0}
             :hoverable false
             :position "left center"
             :transition "fade"
             :duration 100}]
           ^{:key "tooltip-content"}
           [:div.ui.small.popup.transition.hidden.tooltip
            {:style {:min-width "0"
                     :padding-top "0.6em"
                     :padding-bottom "0.6em"}}
            "Switch Theme"]))]
    [:div.ui.menu.site-menu
     [:div.ui.container
      [:a.header.item {:href "/"}
       [:img.ui.middle.aligned.image
        {:src "/SysRev_header.png" :alt "SysRev"
         :width "65" :height "20"}]]
      (when-not full? dev-menu)
      [loading-indicator]
      (if logged-in?
        [:div.right.menu
         (when full? dev-menu)
         (toggle-theme-button)
         [:a.item {:id "user-name-link"
                   ;; :on-click #(dispatch [:navigate [:user-settings]])
                   :href "/user/settings"}
          [:span.blue-text [:i.user.icon] user-display]]
         [:a.item {:id "log-out-link"
                   :on-click #(dispatch [:action [:auth/log-out]])}
          "Log Out"]
         [:div.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         (toggle-theme-button)
         [:a.item.distinct {:id "log-in-link"
                            :on-click #(dispatch [:navigate [:login]])}
          "Log In"]
         [:div.item {:style {:width "0" :padding "0"}}]])]]))
