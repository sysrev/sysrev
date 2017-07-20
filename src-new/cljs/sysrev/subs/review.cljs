(ns sysrev.subs.review
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub reg-sub-raw]]
   [sysrev.subs.ui :refer [active-panel]]))

(defn task-id [db]
  (get-in db [:data :review :task-id]))

(reg-sub :review/task-id task-id)

(reg-sub
 :review/today-count
 (fn [db]
   (get-in db [:data :review :today-count])))

(reg-sub
 :review/on-review-task?
 :<- [:active-panel]
 (fn [panel]
   (= panel [:project :review])))

(reg-sub
 :review/editing-id
 :<- [:review/on-review-task?]
 :<- [:review/task-id]
 (fn [[on-review-task? task-id]]
   (cond on-review-task? task-id)))

(reg-sub
 :review/editing?
 :<- [:review/editing-id]
 (fn [editing-id]
   (if editing-id true false)))
