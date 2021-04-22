(ns sysrev.views.panels.notifications
  (:require [cljs-time.coerce :as tc]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-sub subscribe]]
            [sysrev.state.notifications]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.util :refer [time-elapsed-string]]))

(reg-sub :notifications
         (fn [db & _]
           (get db :notifications)))

(reg-sub :notifications/open?
         (fn [db & _]
           (get db :notifications/open?)))

(reg-event-db :notifications/set-open
              (fn [db [_ open?]]
                (assoc db :notifications/open? open?)))

(defn toggle-open [open?]
  (dispatch [:notifications/set-open (not open?)]))

(reg-event-fx :consume-notification
              (fn [{:keys [db]} [_ {:keys [id uri] :as notification}]]
                (let [notifications (->> db :notifications
                                      (mapv #(if (= notification %)
                                               (assoc notification :viewed (js/Date.))
                                               %)))]
                  {:db (assoc db :notifications notifications)
                   :dispatch-n [[:notifications/set-open false]
                                [:nav uri]]})))

(defn NotificationItem [{:keys [created html image-uri id viewed] :as notification}]
  [:div {:class "notification-item"
         :on-click #(dispatch [:consume-notification notification])}
   [:div
    [:img {:class "notification-item-image"
           :src image-uri}]]
   [:span
    [:span {:dangerouslySetInnerHTML {:__html html}}]
    [:br] [:br]
    [:span {:class "notification-item-time"}
     (time-elapsed-string (tc/from-date created))]]])

(defn NotificationsContainer []
  (let [notifications (->> @(subscribe [:notifications])
                        (remove :viewed)
                        (sort-by :created)
                        reverse)]
    [:div {:class "ui notifications-container"}
     [:div {:class "ui header notifications-title"}
      "Notifications"]
     (into [:div]
       (mapv #(-> [NotificationItem %]) notifications))
     [:div {:class "notifications-footer"
            :on-click #(do (dispatch [:nav "/notifications"])
                           (dispatch [:notifications/set-open false]))}
      "See All"]]))

(defn NotificationsButton []
  (let [notifications @(subscribe [:notifications])
        new-count (count (remove :viewed notifications))
        open? (some-> (subscribe [:notifications/open?]) deref)]
    [:<>
     [:a {:class "item"
          :on-click #(toggle-open open?)}
      [:i {:class "bell outline icon notifications-icon"}]
      (when-not (zero? new-count)
        [:span {:class "notifications-count"}
         new-count])]
     (when open?
       [NotificationsContainer])]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:notifications]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(defn NotificationsPanel []
    (let [notifications @(subscribe [:notifications])]
      [:div {:class "ui panel segment notifications-panel"}
       [:div {:class "ui header notifications-title"}
        "Notifications"]
       (into [:div]
         (mapv #(-> [NotificationItem %]) notifications))]))

(def-panel :uri "/notifications" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content (when-let [_ @(subscribe [:self/user-id])]
             [NotificationsPanel])
  :require-login true)
