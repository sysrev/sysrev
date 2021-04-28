(ns sysrev.state.notifications
  (:require [re-frame.core :refer [reg-event-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.data.core :refer [def-data]]))

(def-data :notifications
  :loaded? (fn [db] (-> (get-in db [:data])
                      (contains? :notifications)))
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications"))
  :process
  (fn [{:keys [db]} _ {:keys [notifications]}]
    {:db (->> notifications
              (map (juxt :notification-id identity))
              (into {})
              (assoc db :notifications))}))

(def-action :notifications/set-viewed
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications/set-viewed"))
  :content (fn [_ notification-ids] {:notification-ids notification-ids})
  :process (fn [_ _ _]))

(reg-event-db :notifications/update-notifications
              (fn [db [_ notifications]]
                (reduce
                 (fn [db [k v]]
                   (assoc-in db [:notifications k] (assoc v :notification-id k)))
                 db
                 notifications)))
