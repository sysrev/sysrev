(ns sysrev.subs.review
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub reg-sub-raw]]
   [sysrev.subs.ui :refer [active-panel]]
   [sysrev.subs.articles :as articles]
   [sysrev.subs.auth :refer [current-user-id]]))

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

(reg-sub
 ::labels
 (fn [db]
   (get-in db [:state :review :labels])))

;; TODO: delete these functions if not needed
#_
(defn review-ui-labels [db article-id]
  (get-in db [:state :review :labels article-id]))
#_
(defn active-labels [db article-id]
  (when-let [user-id (current-user-id db)]
    (merge (articles/article-labels db article-id user-id)
           (review-ui-labels db article-id))))

(reg-sub
 :review/active-labels
 (fn [[_ article-id label-id]]
   [(subscribe [:review/editing-id])
    (subscribe [:self/user-id])
    (subscribe [::labels])
    (subscribe [:article/labels article-id])])
 (fn [[editing-id user-id ui-labels article-labels] [_ article-id label-id]]
   (let [article-id (or article-id editing-id)
         ui-vals (get-in ui-labels [article-id] {})
         article-vals (get-in article-labels [user-id] {})]
     (cond-> (merge article-vals ui-vals)
       label-id (get label-id)))))
