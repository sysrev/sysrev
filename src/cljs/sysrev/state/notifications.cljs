(ns sysrev.state.notifications
  (:require [re-frame.core :refer [reg-event-db reg-event-fx]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.data.core :refer [def-data]]
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
  [[:nav (str "/user/" (get-in notification [:content :user-id]) "/invitations")]])

(def-data :notifications/new
  :loaded? (fn [db] (-> (get-in db [:data])
                      (contains? :notifications)))
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications?consumed=false"))
  :process
  (fn [{:keys [db]} _ {:keys [notifications]}]
    {:db (->> notifications
              (map (juxt :notification-id identity))
              (into {})
              (assoc db :notifications))}))

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
                (reduce
                 (fn [db [k v]]
                   (update-in db [:notifications k]
                              merge (assoc v :notification-id k)))
                 db
                 notifications)))

(reg-event-fx :notifications/consume
              (fn [{:keys [db]} [_ notification]]
                (let [nids (notification-ids notification)
                      now (js/Date.)]
                  {:db
                   (assoc db :notifications
                          (reduce
                           #(update % %2 assoc :consumed now :viewed now)
                           (:notifications db)
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
                   (assoc db :notifications
                          (reduce
                           #(assoc-in % [%2 :viewed] now)
                           (:notifications db)
                           nids))
                   :dispatch-n [(when (seq nids)
                                  [:action [:notifications/set-viewed
                                            (current-user-id db) nids]])]})))
