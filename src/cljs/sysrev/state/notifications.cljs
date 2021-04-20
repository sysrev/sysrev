(ns sysrev.state.notifications
  (:require [re-frame.core :refer [subscribe reg-event-db reg-sub]]
            [sysrev.state.identity :as self]
            [sysrev.data.core :refer [def-data]]))

(def-data :notifications
  :loaded? (fn [db] (-> (get-in db [:data])
                      (contains? :notifications)))
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications"))
  :process
  (fn [{:keys [db]} [user-id] {:keys [notifications]}]
    {:db (assoc db :notifications notifications)}))
