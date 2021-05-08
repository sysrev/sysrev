(ns sysrev.state.notifications
  (:require [cljs-http.client :as http]
            [cljs-time.coerce :as tc]
            [re-frame.core :refer [reg-event-db reg-event-fx reg-sub]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.shared.notifications :refer [uncombine-notification]]
            [sysrev.state.identity :refer [current-user-id]]))

(defn notification-ids [{:keys [notification-id notification-ids]}]
  (if notification-id
    [notification-id]
    notification-ids))

(defmulti consume-notification-dispatches (comp keyword :type :content))

(defmethod consume-notification-dispatches :default [_]
  [])

(defmethod consume-notification-dispatches :article-reviewed [notification]
  (let [{:keys [article-id project-id]} (:content notification)]
    [[:nav (str "/p/" project-id "/article/" article-id)]]))

(defmethod consume-notification-dispatches :article-reviewed-combined
  [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]) "/articles")]])

(defmethod consume-notification-dispatches :notify-user
  [notification]
  (when-let [uri (get-in notification [:content :uri])]
    [[:nav uri]]))

(defmethod consume-notification-dispatches :group-has-new-project [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]))]])

(defmethod consume-notification-dispatches :project-has-new-article [notification]
  (let [{:keys [article-id project-id]} (:content notification)]
    [[:nav (str "/p/" project-id "/article/" article-id)]]))

(defmethod consume-notification-dispatches :project-has-new-article-combined
  [notification]
  [[:project/navigate (get-in notification [:content :project-id]) "/articles" :params {:sort-by "article-added"}]])

(defmethod consume-notification-dispatches :project-has-new-user [notification]
  [[:nav (str "/p/" (get-in notification [:content :project-id]) "/users")]])

(defmethod consume-notification-dispatches :project-has-new-user-combined
  [notification]
  (consume-notification-dispatches
   (assoc-in notification [:content :type] :project-has-new-user)))

(defmethod consume-notification-dispatches :project-invitation [notification]
  [[:nav (str "/user/" (get-in notification [:content :user-id]) "/invitations")]
])

(defmethod consume-notification-dispatches :system
  [notification]
  (when-let [uri (get-in notification [:content :uri])]
    [[:nav uri]]))

(defn merge-notifications [db notifications]
  (->> (mapcat uncombine-notification notifications)
       (reduce
        (fn [m {:keys [notification-id] :as notification}]
          (update m notification-id merge notification))
        (get-in db [:data :notifications]))
       (assoc-in db [:data :notifications])))

(def-data :notifications/by-day
  :loaded? (fn [db & args]
             (get-in db [:state :notifications :loaded-by-day args]))
  :uri (fn [user-id & {:as query-params}]
         (apply str "/api/user/" user-id "/notifications/by-day"
              (when (seq query-params)
                ["?" (http/generate-query-string query-params)])))
  :process
  (fn [{:keys [db]}
       [_user-id & {:keys [start-at]} :as args]
       {:keys [next-created-after notifications]}]
    {:db (-> (merge-notifications db notifications)
             (assoc-in [:state :notifications :loaded-by-day args] true)
             (assoc-in [:state :notifications :at-end?] (empty? notifications))
             (assoc-in [:state :notifications :next-created-after]
                       (tc/to-long (tc/from-date next-created-after))))}))

(def-data :notifications/new
  :loaded? (fn [db]
             (get-in db [:state :notifications :loaded-new?]))
  :uri (fn [user-id & {:keys [limit] :or {limit 5}}]
         (str "/api/user/" user-id "/notifications/new?limit=" limit))
  :process
  (fn [{:keys [db]} _ {:keys [notifications]}]
    {:db (-> (merge-notifications db notifications)
             (assoc-in [:state :notifications :loaded-new?] true))}))

(def-action :notifications/set-consumed
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications/set-consumed"))
  :content (fn [_ notification-ids] {:notification-ids notification-ids})
  :process (fn [_ _ _]))

(def-action :notifications/set-viewed
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications/set-viewed"))
  :content (fn [_ notification-ids] {:notification-ids notification-ids})
  :process (fn [_ _ _]))

(reg-event-db :notifications/update-notifications
              (fn [db [_ notifications]]
                (->> notifications
                     (map (fn [[k v]] (assoc v :notification-id k)))
                     (merge-notifications db))))

(reg-event-fx :notifications/consume
              (fn [{:keys [db]} [_ notification]]
                (let [nids (->> notification notification-ids
                                (remove #(get-in db [:data :notifications % :consumed])))
                      now (js/Date.)]
                  {:db
                   (assoc-in db [:data :notifications]
                          (reduce
                           #(update % %2 assoc :consumed now :viewed now)
                           (get-in db [:data :notifications])
                           nids))
                   :dispatch-n
                   (into
                    [(when (seq nids)
                       [:action [:notifications/set-consumed
                                 (current-user-id db) nids]])]
                    (consume-notification-dispatches notification))})))

(reg-event-fx :notifications/view
              (fn [{:keys [db]} [_ notifications]]
                (let [nids (->> (remove :viewed notifications)
                                (mapcat notification-ids))
                      now (js/Date.)]
                  {:db
                   (assoc-in db [:data :notifications]
                          (reduce
                           #(assoc-in % [%2 :viewed] now)
                           (get-in db [:data :notifications])
                           nids))
                   :dispatch-n [(when (seq nids)
                                  [:action [:notifications/set-viewed
                                            (current-user-id db) nids]])]})))

(reg-sub :notifications
         (fn [db & _]
           (get-in db [:data :notifications])))

(reg-sub :notifications/at-end?
         (fn [db & _]
           (get-in db [:state :notifications :at-end?])))

(reg-sub :notifications/next-created-after
         (fn [db & _]
           (get-in db [:state :notifications :next-created-after])))
