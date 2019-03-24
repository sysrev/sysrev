(ns sysrev.views.menu
  (:require  [re-frame.core :refer [subscribe dispatch dispatch-sync]]
             [sysrev.loading :as loading]
             [sysrev.nav :refer [nav nav-scroll-top]]
             [sysrev.views.components :refer [dropdown-menu with-tooltip]]
             [sysrev.views.panels.user.profile :refer [Avatar]]
             [sysrev.util :as util])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn loading-indicator []
  (if @(loading/loading-indicator)
    [:div.item.loading-indicator>div.ui.small.active.inline.loader]
    [:div.item.loading-indicator-disabled]))

(defn toggle-ui-theme [logged-in? settings]
  (let [new-theme (if (= "Dark" (:ui-theme settings)) "Default" "Dark")]
    (if logged-in?
      (dispatch [:action [:user/change-settings [{:setting :ui-theme, :value new-theme}]]])
      (dispatch [:action [:session/change-settings (merge settings {:ui-theme new-theme})]]))))

(defn header-menu []
  (let [logged-in? @(subscribe [:self/logged-in?])
        user-id @(subscribe [:self/user-id])
        user-display @(subscribe [:user/display])
        admin? @(subscribe [:user/admin?])
        project-ids @(subscribe [:user/project-ids])
        [full? mobile?] [(util/full-size?) (util/mobile?)]
        dev-menu (when admin?
                   [dropdown-menu [{:content "Clear query cache"
                                    :action #(dispatch [:action [:dev/clear-query-cache]])}]
                    :dropdown-class "dropdown item"
                    :label [:i.fitted.code.icon]])
        settings @(subscribe [:self/settings])
        toggle-theme-button (fn []
                              (list ^{:key "tooltip-elt"}
                                    [with-tooltip
                                     [:a.item.toggle-theme
                                      {:on-click #(toggle-ui-theme logged-in? settings)}
                                      [:span {:style {:font-size "22px"}}
                                       [:i.fitted.lightbulb.outline.icon]]]
                                     {:delay {:show 500 :hide 0}
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
    [:div.ui.menu.site-menu>div.ui.container
     [:a.header.item {:href "/"}
      [:img.ui.middle.aligned.image
       (merge {:src "/SysRev_header_2.png" :alt "SysRev"}
              (if mobile? {:width "80" :height "25"} {:width "90" :height "28"}))]]
     (when-not full? dev-menu)
     [loading-indicator]
     (if logged-in?
       [:div.right.menu
        (when full? dev-menu)
        (toggle-theme-button)
        [:a.item {:id "user-name-link"
                  :href "/user/settings"}
         [:div
          [Avatar {:user-id user-id}]
          [:span.blue-text {:style {:margin-left "0.25em"}} user-display]]]
        [:a.item {:id "log-out-link"
                  :on-click #(dispatch [:action [:auth/log-out]])}
         "Log Out"]
        [:div.item {:style {:width "0" :padding "0"}}]]
       ;; not logged in
       [:div.right.menu
        (toggle-theme-button)
        (when (= :main @(subscribe [:app-id]))
          [:a.item.distinct
           {:id "log-in-link"
            :on-click (util/wrap-user-event
                       #(do (dispatch-sync [:set-login-redirect-url (util/get-url-path)])
                            (dispatch [:navigate [:login]])))}
           "Log In"])
        [:div.item {:style {:width "0" :padding "0"}}]])]))
