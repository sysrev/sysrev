(ns sysrev.views.menu
  (:require [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.action.core :refer [run-action]]
            [sysrev.data.core :refer [load-data]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [user-uri]]
            [sysrev.views.components.core :refer [dropdown-menu with-tooltip]]
            [sysrev.views.panels.notifications :refer [NotificationsButton]]
            [sysrev.views.panels.user.profile :refer [Avatar]]
            [sysrev.views.panels.search :refer [SiteSearch]]
            [sysrev.util :as util]))

(defn loading-indicator []
  (if @loading/loading-indicator
    [:div.item.loading-indicator
     [:div.ui.small.active.inline.loader.loading-indicator]]
    [:div.item.loading-indicator-disabled]))

(defn toggle-ui-theme [logged-in? settings]
  (let [new-theme (if (= "Dark" (:ui-theme settings)) "Default" "Dark")]
    (if logged-in?
      (run-action :user/change-settings [{:setting :ui-theme :value new-theme}])
      (run-action :session/change-settings (assoc settings :ui-theme new-theme)))))

(defn header-menu []
  (let [logged-in? @(subscribe [:self/logged-in?])
        landing? @(subscribe [:landing-page?])
        user-id @(subscribe [:self/user-id])
        user-display @(subscribe [:user/display])
        [full? mobile?] [(util/full-size?) (util/mobile?)]
        dev-menu (when @(subscribe [:user/dev?])
                   [dropdown-menu [{:content "Clear query cache"
                                    :action #(run-action :dev/clear-query-cache)}]
                    :dropdown-class "dropdown item"
                    :label [:i.fitted.code.icon]])
        settings @(subscribe [:self/settings])
        toggle-theme-button (fn []
                              (when-not landing?
                                (list ^{:key "tooltip-elt"}
                                      [with-tooltip
                                       [:a.item.toggle-theme
                                        {:on-click #(toggle-ui-theme logged-in? settings)}
                                        [:span {:style {:font-size "22px"}}
                                         [:i.fitted.lightbulb.outline.icon]]]
                                       {:delay {:show 350 :hide 50}
                                        :hoverable false
                                        :position "left center"
                                        :transition "fade"
                                        :duration 100}]
                                      ^{:key "tooltip-content"}
                                      [:div.ui.small.popup.transition.hidden.tooltip
                                       {:style {:min-width "0"
                                                :padding "0.5em 1em"
                                                :font-size "12px"}}
                                       [:span.open-sans.medium-weight "Switch Theme"]])))]
    [:div.ui.menu.site-menu {:class (when landing? "landing")}
     [:div.ui.container
      [:a.header.item {:href "/"}
       [:img.ui.middle.aligned.image
        (merge {:src "/SysRev_header_2.png" :alt "SysRev"}
               (if mobile? {:width "80" :height "25"} {:width "90" :height "28"}))]]
      #_(when (and logged-in? (= "/" (uri-utils/getPath @active-route))))
      [:a.item.distinct {:id "pricing-link" :href "/pricing"} "Pricing"]
      (when-not full? dev-menu)
      [loading-indicator]
      (if logged-in?
        [:div.right.menu
         (when full? dev-menu)
         (toggle-theme-button)
         (when-not mobile? [SiteSearch])
         [:a.item {:id "user-name-link" :href (user-uri user-id)}
          [:div
           [Avatar {:user-id user-id}]
           [:span.blue-text {:style {:margin-left "0.25em"}} user-display]]]
         [NotificationsButton]
         [:a.item {:id "log-out-link" :on-click #(run-action :auth/log-out)}
          "Log Out"]
         [:div.item {:style {:width "0" :padding "0"}}]]
        ;; not logged in
        [:div.right.menu
         (toggle-theme-button)
         (when-not mobile? [SiteSearch])
         [:a.item.distinct
          {:id "log-in-link"
           :on-click (util/wrap-user-event
                      #(do (dispatch-sync [:set-login-redirect-url (util/get-url-path)])
                           (dispatch [:nav "/login"])))}
          "Log In"]
         [:div.item {:style {:width "0" :padding "0"}}]])]]))
