(ns sysrev.state.notifications
  (:require [sysrev.data.core :refer [def-data]]))

(def-data :notifications
  :loaded? (fn [db] (-> (get-in db [:data])
                      (contains? :notifications)))
  :uri (fn [user-id] (str "/api/user/" user-id "/notifications"))
  :process
  (fn [{:keys [db]} _ {:keys [notifications]}]
    {:db (assoc db :notifications notifications)}))
