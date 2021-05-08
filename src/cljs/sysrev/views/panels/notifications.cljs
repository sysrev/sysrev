(ns sysrev.views.panels.notifications
  (:require [cljs-time.coerce :as tc]
            [cljs-time.format :as tf]
            [clojure.string :as str]
            [reagent.core :refer [create-element]]
            [re-frame.core :refer [dispatch reg-event-db reg-sub subscribe]]
            [sysrev.data.core :as data]
            [sysrev.shared.notifications :refer [combine-notifications]]
            [sysrev.state.notifications]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.util :as util :refer [time-elapsed-string]]
            ["react-infinite-scroll-component" :as InfiniteScroll]))

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

(defmethod NotificationDisplay :notify-user [notification]
  [:span (get-in notification [:content :text])])

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
  (let [{{:keys [inviter-name project-name]} :content} notification]
    [:span
     [:b inviter-name]
     " invited you to a project: "
     [:b project-name]
     "."]))

(defmethod NotificationDisplay :system [notification]
  [:span (get-in notification [:content :text])])

(reg-sub :notifications/open?
         (fn [db & _]
           (get-in db [:state :notifications :open?])))

(reg-event-db :notifications/set-open
              (fn [db [_ open?]]
                (assoc-in db [:state :notifications :open?] open?)))

(defn toggle-open [open?]
  (dispatch [:notifications/set-open (not open?)]))

(defn NotificationItem [{:keys [created]
                         {:keys [image-uri]} :content
                         :as notification}]
  [:div {:class "notification-item"
         :on-click #(dispatch [:notifications/consume notification])}
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
        user-id (or @(subscribe [:self/user-id]) "")
        new-notifications (->> notifications
                               vals
                               (remove :consumed)
                               combine-notifications
                               (sort-by :created)
                               reverse
                               (take 5))]
    (some->> (remove :viewed new-notifications)
             (vector :notifications/view)
             dispatch)
    [:div {:class "ui notifications-container"}
     [:div {:class "ui notifications-title"}
      "Notifications"]
     (if (empty? new-notifications)
       (if (empty? notifications)
         [:div {:class "notifications-empty-message"}
          "You don't have any notifications yet."]
         [:div {:class "notifications-empty-message"}
          "You don't have any new notifications. Click "
          [:a {:href (str "/user/" user-id "/notifications")
               :on-click #(dispatch [:notifications/set-open false])}
           "See All"]
          " to see older notifications."])
       (into [:div]
             (mapv #(-> [NotificationItem %]) new-notifications)))
     [:div {:class "notifications-footer"
            :on-click #(do (dispatch [:nav (str "/user/" user-id "/notifications")])
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

(defn InfiniteNotifications [user-id next-created-after at-end? children]
  (into
   [:> InfiniteScroll
    {:endMessage (create-element "div" #js{:className "no-more-notifications"}
                                 "That's everything!")
     :dataLength (count children)
     :hasMore (not at-end?)
     :loader (create-element "div" #js{:className "loading-notifications"}
                             "Loading...")
     :next #(data/require-data :notifications/by-day user-id
                               :created-after next-created-after)}]
   children))


(def year-month-day-formatter (tf/formatter "yyyy-MM-dd"))
(def month-day-formatter (tf/formatter "MMMM dd"))

(defn day-grouping [{:keys [created]}]
  (when created
    (tf/unparse year-month-day-formatter (tc/from-date created))))

(defn NotificationsPanel []
  (let [user-id @(subscribe [:self/user-id])
        next-created-after @(subscribe [:notifications/next-created-after])
        at-end? @(subscribe [:notifications/at-end?])
        notifications (vals @(subscribe [:notifications]))
        children (->> notifications
                      (group-by day-grouping)
                      (map (fn [[k v]] [k (combine-notifications v)]))
                      sort
                      reverse
                      (mapcat
                       (fn [[_ ntfcns]]
                         (let [date (-> ntfcns first :created tc/from-date)]
                           (into
                            [[:div {:class "notifications-date-header"}
                              (tf/unparse month-day-formatter date)]]
                            (map #(-> [NotificationItem %]) ntfcns))))))]
    (when (and (not at-end?) (> 30 (count children)))
      (data/require-data :notifications/by-day user-id
                         :created-after next-created-after))
    (some->> (remove :viewed notifications)
             (vector :notifications/view)
             dispatch)
    [:div {:class "ui panel segment notifications-panel"}
     [:div {:class "ui notifications-title"}
      "Notifications"]
     (if (empty? children)
       [:div {:class "notifications-empty-message"}
        "You don't have any notifications yet."]
       [InfiniteNotifications user-id next-created-after at-end? children])]))

(def-panel :uri "/user/:user-id/notifications" :params [user-id] :panel panel
  :on-route (do (dispatch [:set-active-panel panel])
                (dispatch [:require [:notifications/by-day (util/parse-integer user-id)]]))
  :content (when-let [_ @(subscribe [:self/user-id])]
             [NotificationsPanel])
  :require-login true)
