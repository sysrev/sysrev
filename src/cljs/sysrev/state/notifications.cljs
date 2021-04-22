(ns sysrev.state.notifications
  (:require [sysrev.action.core :refer [def-action]]
            [sysrev.data.core :refer [def-data]]))

(def-data :notifications
  :loaded? (fn [db] (-> (get-in db [:data])
                      (contains? :notifications)))
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications"))
  :process
  (fn [{:keys [db]} _ {:keys [notifications]}]
    {:db (assoc db :notifications notifications)}))

(def-action :notifications/set-viewed
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications/set-viewed"))
  :content (fn [_ message-id] {:message-id message-id})
  :process (fn [_ _ _]))
