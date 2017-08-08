(ns sysrev.events.project
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.subs.project :refer [active-project-id]]))

(reg-event-db
 :project/load
 [trim-v]
 (fn [db [{:keys [project-id] :as pmap}]]
   (update-in db [:data :project project-id]
              #(merge % pmap))))

(reg-event-db
 :project/load-settings
 [trim-v]
 (fn [db [project-id settings]]
   (assoc-in db [:data :project project-id :settings] settings)))

(reg-event-db
 :project/clear-data
 [trim-v]
 (fn [db]
   (if-let [project-id (active-project-id db)]
     (dissoc-in db [:data :project project-id])
     db)))

(reg-event-db
 :project/load-label-activity
 [trim-v]
 (fn [db [label-id content]]
   (let [project-id (active-project-id db)]
     (assoc-in db [:data :project project-id :label-activity label-id]
               content))))
