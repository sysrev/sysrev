(ns sysrev.views.panels.notifications
  (:require [cljs-time.coerce :as tc]
            [clojure.string :as str]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-sub subscribe]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.state.notifications]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.util :refer [time-elapsed-string]]))

(defmulti MessageDisplay (comp keyword :type :content))

(defmethod MessageDisplay :default [notification]
  [:span (pr-str notification)])

(defmethod MessageDisplay :project-has-new-user [notification]
  (let [{{:keys [new-user-name project-name]} :content} notification]
    [:span
     [:b new-user-name]
     " joined "
     [:b project-name]]))

(defmethod MessageDisplay :project-invitation [notification]
  (let [{{:keys [project-name]} :content} notification]
    [:span
     "You were invited to a project: "
     [:b project-name]]))

(defmulti consume-notification-dispatches (comp keyword :type :content))

(defmethod consume-notification-dispatches :default [_]
  [])

(defmethod consume-notification-dispatches :project-has-new-user [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]) "/users")]])

(defmethod consume-notification-dispatches :project-invitation [notification]
  [[:nav (str "/user/" (get-in notification [:content :user-id]) "/invitations")]])

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
              (fn [{:keys [db]} [_ {:keys [message-id] :as message}]]
                {:db (assoc-in db [:notifications message-id :viewed]
                               (js/Date.))
                 :dispatch-n (concat
                              [[:notifications/set-open false]
                               (when-not (:viewed message)
                                 [:action
                                  [:notifications/set-viewed
                                   (current-user-id db)
                                   message-id]])]
                              (consume-notification-dispatches message))}))

(defn NotificationItem [{:keys [created]
                         {:keys [image-uri]} :content
                         :as notification}]
  [:div {:class "notification-item"
         :on-click #(dispatch [:consume-notification notification])}
   [:div
    [:img {:class "notification-item-image"
           :src (or image-uri "/favicon-32x32.png")}]]
   [:span
    [MessageDisplay notification]
    [:br] [:br]
    [:span {:class "notification-item-time"}
     (str/capitalize
      (time-elapsed-string (tc/from-date created)))]]])

(defn NotificationsContainer []
  (let [notifications @(subscribe [:notifications])
        new-notifications (->> notifications
                               vals
                               (remove :viewed)
                               (sort-by :created)
                               reverse)]
    [:div {:class "ui notifications-container"}
     [:div {:class "ui header notifications-title"}
      "Notifications"]
     (if (empty? new-notifications)
       (if (empty? notifications)
         [:div {:class "notifications-empty-message"}
          "You don't have any notifications yet."]
         [:div {:class "notifications-empty-message"}
          "You don't have any new notifications. Click "
          [:a {:href "/notifications"}
           "See All"]
          " to see older notifications."])
       (into [:div]
         (mapv #(-> [NotificationItem %]) new-notifications)))
     [:div {:class "notifications-footer"
            :on-click #(do (dispatch [:nav "/notifications"])
                           (dispatch [:notifications/set-open false]))}
      "See All"]]))

(defn NotificationsButton []
  (let [notifications @(subscribe [:notifications])
        new-count (->> notifications vals (remove :viewed) count)
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
  (let [notifications (->> @(subscribe [:notifications])
                           vals
                           (sort-by :created)
                           reverse)]
      [:div {:class "ui panel segment notifications-panel"}
       [:div {:class "ui header notifications-title"}
        "Notifications"]
       (if (empty? notifications)
         [:div {:class "notifications-empty-message"}
          "You don't have any notifications yet."]
         (into [:div]
           (mapv #(-> [NotificationItem %]) notifications)))]))

(def-panel :uri "/notifications" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content (when-let [_ @(subscribe [:self/user-id])]
             [NotificationsPanel])
  :require-login true)
