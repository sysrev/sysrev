(ns sysrev.data.definitions
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.data.core :refer [def-data]]))

;;
;; Definitions for all data items fetched from server
;;

(def-data :identity
  :sub (fn [] [:sysrev.subs.auth/identity])
  :uri (fn [] "/api/auth/identity")
  :process
  (fn [_ _ {:keys [identity active-project]}]
    {:dispatch-n
     (list [:set-identity identity]
           [:set-active-project active-project]
           [:user/store identity])}))

(def-data :project
  :sub (fn [] [:project/raw])
  :uri (fn [] "/api/project-info")
  :prereqs (fn [] [[:active-project-id]])
  :process
  (fn [_ _ {:keys [project users]}]
    {:dispatch-n
     (list [:project/load project]
           [:user/store-multi (vals users)])}))

(def-data :member/labels
  :sub (fn [user-id] [:member/labels user-id])
  :uri (fn [user-id] (str "/api/member-labels/" user-id))
  :process
  (fn [_ [user-id] {:keys [articles labels notes]}]
    ;; TODO - store result
    {}))

(def-data :project/label-activity
  :sub (fn [label-id] [:label-activity/raw label-id])
  :uri (fn [label-id] (str "/api/label-activity/" label-id))
  :prereqs (fn [label-id] [[:project/have-label? label-id]])
  :process
  (fn [_ [label-id] result]
    {:dispatch [:project/load-label-activity label-id result]}))
