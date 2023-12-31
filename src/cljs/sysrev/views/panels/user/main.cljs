(ns sysrev.views.panels.user.main
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer [Icon]]
            [sysrev.util :as util :refer [css mobile?]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:user]
                   :get [panel-get ::get] :set [panel-set ::set])

(reg-sub :user/get (fn [db [_ user-id]] (get-in db [:data :users-public user-id])))

(reg-sub :user-panel/user-id
         :<- [::get :user-id]
         identity)

;; Checks if user-id from a user panel is the current logged-in user.
(reg-sub :user-panel/self?
         :<- [:self/user-id]
         :<- [:user-panel/user-id]
         (fn [[self-id user-id] _]
           (and user-id (= user-id self-id))))

(reg-event-db :user-panel/set-user-id [trim-v]
              (fn [db [user-id]]
                (panel-set db :user-id user-id)))

(defn InvitationsIcon []
  (let [invitations @(subscribe [:user/invitations])]
    (when-not (empty? (filter #(nil? (:accepted (val %))) invitations))
      [Icon {:name "circle" :size "tiny" :color "orange"
             :style {:margin-left "0.5em"}}])))

(defn UserNavMenu [child]
  (let [path-id @(subscribe [:user-panel/user-id])
        self-id @(subscribe [:self/user-id])
        self? @(subscribe [:user-panel/self?])
        uri-fn (fn [sub-path] (str "/user/" path-id sub-path))
        payments-owed @(subscribe [:user/payments-owed])
        payments-paid @(subscribe [:user/payments-paid])
        invitations @(subscribe [:user/invitations])
        ;; drop the :user from [:user ...], take first keyword
        active-tab (->> @(subscribe [:active-panel]) (drop 1) first)
        active (fn [panel-key] (css "item" [(= panel-key active-tab) "active"]))]
    (when self?
      (dispatch [:require [:user/payments-owed self-id]])
      (dispatch [:require [:user/payments-paid self-id]])
      (dispatch [:data/load [:user/invitations self-id]])
      (dispatch [:data/load [:user/current-plan self-id]]))
    [:div
     [:nav
      [:div.ui.secondary.pointing.menu.primary-menu.bottom.attached
       {:class (css [(mobile?) "tiny"])}
       [:a#user-profile {:class (active :profile) :href (uri-fn "/profile")}
        "Profile"]
       [:a#user-projects {:class (active :projects) :href (uri-fn "/projects")}
        "Projects"]
       ;; should check if orgs exist
       [:a#user-orgs {:class (active :orgs) :href (uri-fn "/orgs")}
        (if (mobile?) "Orgs" "Organizations")]
       (when self?
         [:div.right.menu
          [:a#user-billing {:class (active :billing) :href (uri-fn "/billing")}
           (if (mobile?) [:i.money.icon] "Billing")]
          [:a#user-email {:class (active :email) :href (uri-fn "/email")}
           (if (mobile?) [:i.mail.icon] "Email")]
          (when (seq (or payments-owed payments-paid))
            [:a#user-compensation {:class (active :compensation) :href (uri-fn "/compensation")}
             "Compensation"])
          (when (seq invitations)
            [:a#user-invitations {:class (active :invitations) :href (uri-fn "/invitations")}
             "Invitations" [InvitationsIcon]])
          [:a#user-settings {:class (active :settings) :href (uri-fn "/settings")}
           (if (mobile?) [:i.settings.icon] "Settings")]])]]
     child]))

(defmethod panel-content panel []
  (fn [child] [UserNavMenu child]))
