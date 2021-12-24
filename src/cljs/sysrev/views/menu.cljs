(ns sysrev.views.menu
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.action.core :refer [run-action]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [user-uri]]
            [sysrev.views.semantic :as S]
            [sysrev.views.panels.notifications :refer [NotificationsButton]]
            [sysrev.views.panels.user.profile :refer [Avatar]]
            [sysrev.views.panels.search :refer [SiteSearch]]
            [sysrev.util :as util]
            [sysrev.shared.components :as shared]))

(defn loading-indicator []
  [shared/loading-indicator @loading/loading-indicator])

(defn toggle-ui-theme [logged-in? settings]
  (let [new-theme (if (= "Dark" (:ui-theme settings)) "Default" "Dark")]
    (if logged-in?
      (run-action :user/change-settings [{:setting :ui-theme :value new-theme}])
      (run-action :session/change-settings (assoc settings :ui-theme new-theme)))))

(defn header-menu []
  (let [logged-in? @(subscribe [:self/logged-in?])
        landing? @(subscribe [:landing-page?])
        user-id @(subscribe [:self/user-id])
        username @(subscribe [:user/username])
        [full? mobile?] [(util/full-size?) (util/mobile?)]
        dev-menu (when @(subscribe [:user/dev?])
                   [S/Dropdown {:class "item"
                                :simple true
                                :direction "left"
                                :icon "code"
                                :size "small"}
                    [S/DropdownMenu {:style {:padding "0em 0.5em"}}
                     (for [{:keys [action content] :as entry}
                           [{:content "Clear query cache"
                             :action #(run-action :dev/clear-query-cache)}]]
                       ^{:key entry}
                       [S/DropdownItem
                        {:text content
                         :href (some->> action (util/when-test string?))
                         :on-click (some->> action (util/when-test (complement string?))
                                            util/wrap-user-event)}])]])
        settings @(subscribe [:self/settings])
        toggle-theme-button
        (fn []
          (when-not landing?
            [S/Popup
             {:size "small" :inverted true :hoverable false
              :position "left center"
              :mouse-enter-delay 250 :mouse-leave-delay 50
              :transition "fade" :duration 100
              :trigger (r/as-element [:a.item.toggle-theme
                                      {:on-click #(toggle-ui-theme logged-in? settings)}
                                      [:span {:style {:font-size "22px"}}
                                       [:i.fitted.lightbulb.outline.icon]]])
              :content (r/as-element
                        [:div {:style {:min-width "0"
                                       :font-size "12px"}}
                         [:span.open-sans.medium-weight "Switch Theme"]])}]))]
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
           [:span.blue-text {:style {:margin-left "0.25em"}} username]]]
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
