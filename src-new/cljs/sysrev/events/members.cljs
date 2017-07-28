(ns sysrev.events.members
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v]]
   [sysrev.subs.project :as project]
   [sysrev.subs.auth :as auth]))

(reg-event-db
 :member/load-articles
 [trim-v]
 (fn [db [user-id articles]]
   (let [project-id (project/active-project-id db)]
     (assoc-in db [:data :project project-id :member-articles user-id]
               articles))))
