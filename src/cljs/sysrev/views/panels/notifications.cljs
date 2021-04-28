(ns sysrev.views.panels.notifications
  (:require [cljs-time.coerce :as tc]
            [clojure.string :as str]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-sub subscribe]]
            [sysrev.shared.notifications :refer [combine-notifications]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.state.notifications]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.util :refer [time-elapsed-string]]))

(defn comma-and-join [coll]
  (let [xs (remove empty? coll)
        ct (count xs)]
    (case ct
      0 nil
      1 xs
      2 [(first xs) " and " (second xs)]
      (interleave xs (concat (repeat (- ct 2) ", ") [", and " ""])))))

(defmulti NotificationDisplay (comp keyword :type :content))

(defmethod NotificationDisplay :default [notification]
  [:span (pr-str notification)])

(defmethod NotificationDisplay :article-reviewed [notification]
  (let [{{:keys [article-data-title project-name]} :content} notification]
    [:span
     [:b project-name]
     " has a new article review: "
     [:b article-data-title]
     "."]))

(defmethod NotificationDisplay :article-reviewed-combined [notification]
  (let [{{:keys [article-count project-name]} :content} notification]
    [:span
     [:b project-name]
     " has "
     [:b article-count]
     " new article reviews."]))

(defmethod NotificationDisplay :group-has-new-project [notification]
  (let [{{:keys [group-name project-name]} :content} notification]
    [:span
     "The "
     [:b project-name]
     " project was added in the "
     [:b group-name]
     " organization."]))

(defmethod NotificationDisplay :project-has-new-article [notification]
  (let [{{:keys [adding-user-name article-data-title project-name]} :content} notification]
    [:span
     [:b adding-user-name]
     " added a new article to "
     [:b project-name]
     ": "
     [:b article-data-title]
     "."]))

(defmethod NotificationDisplay :project-has-new-article-combined [notification]
  (let [{{:keys [article-count project-name]} :content} notification]
    [:span
     [:b article-count]
     " new articles were added to "
     [:b project-name]
     "."]))

(defmethod NotificationDisplay :project-has-new-user [notification]
  (let [{{:keys [new-user-name project-name]} :content} notification]
    [:span
     [:b new-user-name]
     " joined "
     [:b project-name]
     "."]))

(defmethod NotificationDisplay :project-has-new-user-combined [notification]
  (let [{{:keys [new-user-names project-name]} :content} notification
        ct (count new-user-names)]
    [:span
     [:b ct]
     " new users joined "
     [:b project-name]
     ": "
     (->> new-user-names
          (map #(-> [:b %]))
          comma-and-join
          (into [:<>]))
     "."]))

(defmethod NotificationDisplay :project-invitation [notification]
  (let [{{:keys [project-name]} :content} notification]
    [:span
     "You were invited to a project: "
     [:b project-name]
     "."]))

(defmulti consume-notification-dispatches (comp keyword :type :content))

(defmethod consume-notification-dispatches :default [_]
  [])

(defmethod consume-notification-dispatches :article-reviewed [notification]
  (let [{:keys [article-id project-id]} (:content notification)]
    [[:nav (str "/p/" project-id "/article/" article-id)]]))

(defmethod consume-notification-dispatches :article-reviewed-combined
  [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]) "/articles")]])

(defmethod consume-notification-dispatches :group-has-new-project [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]))]])

(defmethod consume-notification-dispatches :project-has-new-article [notification]
  (let [{:keys [article-id project-id]} (:content notification)]
    [[:nav (str "/p/" project-id "/article/" article-id)]]))

(defmethod consume-notification-dispatches :project-has-new-article-combined
  [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]) "/articles")]])

(defmethod consume-notification-dispatches :project-has-new-user [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]) "/users")]])

(defmethod consume-notification-dispatches :project-has-new-user-combined
  [notification]
  (consume-notification-dispatches
   (assoc-in notification [:content :type] :project-has-new-user)))

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
              (fn [{:keys [db]} [_ notification]]
                (let [nids (:notification-ids notification [(:notification-id notification)])
                      now (js/Date.)]
                  {:db
                   (assoc db :notifications
                          (reduce
                           #(assoc-in % [%2 :viewed] now)
                           (:notifications db)
                           nids))
                   :dispatch-n (concat
                                [[:notifications/set-open false]
                                 (when-not (:viewed notification)
                                   [:action
                                    [:notifications/set-viewed
                                     (current-user-id db)
                                     nids]])]
                                (consume-notification-dispatches notification))})))

(defn NotificationItem [{:keys [created]
                         {:keys [image-uri]} :content
                         :as notification}]
  [:div {:class "notification-item"
         :on-click #(dispatch [:consume-notification notification])}
   [:div
    [:img {:class "notification-item-image"
           :src (or image-uri "/apple-touch-icon.png")
           :style {:max-height "60px"
                   :width "60px"}}]]
   [:span
    [NotificationDisplay notification]
    [:br] [:br]
    (when created
      [:span {:class "notification-item-time"}
       (str/capitalize
        (time-elapsed-string (tc/from-date created)))])]])

(defn NotificationsContainer []
  (let [notifications @(subscribe [:notifications])
        new-notifications (->> notifications
                               vals
                               (remove :viewed)
                               combine-notifications
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
        new-count (->> notifications vals (remove :viewed)
                       combine-notifications count)
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
                           (combine-notifications
                            (fn [{:keys [created]}]
                              (when created
                                [(.getYear created) (.getMonth created) (.getDate created)])))
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
