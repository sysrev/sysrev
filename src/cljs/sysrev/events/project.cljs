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
 :project/load-sources
 [trim-v]
 (fn [db [project-id metadata]]
   (assoc-in db [:data :project project-id :sources] metadata)))

(reg-event-db
 :project/load-files
 [trim-v]
 (fn [db [project-id files]]
   (assoc-in db [:data :project project-id :files] files)))

(reg-event-db
 :project/clear-data
 [trim-v]
 (fn [db]
   (if-let [project-id (active-project-id db)]
     (dissoc-in db [:data :project project-id])
     db)))

(reg-event-db
 :project/load-public-labels
 [trim-v]
 (fn [db [content]]
   (let [project-id (active-project-id db)]
     (assoc-in db [:data :project project-id :public-labels]
               content))))
