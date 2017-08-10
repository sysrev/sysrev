(ns sysrev.subs.review
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub reg-sub-raw]]
   [reagent.ratom :refer [reaction]]
   [sysrev.subs.ui :refer [active-panel]]
   [sysrev.subs.articles :as articles]
   [sysrev.subs.auth :refer [current-user-id]]
   [sysrev.shared.util :refer [in? map-values]]))

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
 :<- [:article-list/editing?]
 :<- [:article-list/article-id]
 :<- [:user-labels/editing?]
 :<- [:user-labels/article-id]
 (fn [[on-review-task? task-aid list-editing? list-aid user-editing? user-aid]]
   (cond on-review-task? task-aid
         list-editing? list-aid
         user-editing? user-aid)))

(reg-sub
 :review/editing?
 :<- [:review/editing-id]
 (fn [editing-id]
   (if editing-id true false)))

(reg-sub-raw
 :review/resolving?
 (fn []
   (reaction
    (when-let [article-id @(subscribe [:review/editing-id])]
      (let [status @(subscribe [:article/review-status article-id])
            resolver? @(subscribe [:member/resolver?])]
        (and (= status "conflict") resolver?))))))

(reg-sub
 ::labels
 (fn [db]
   (get-in db [:state :review :labels])))

(defn review-ui-labels [db article-id]
  (get-in db [:state :review :labels article-id]))

(defn active-labels [db article-id]
  (when-let [user-id (current-user-id db)]
    (merge (->> (articles/article-labels db article-id user-id)
                (map-values :answer))
           (review-ui-labels db article-id))))

(reg-sub
 :review/active-labels
 (fn [[_ article-id label-id]]
   [(subscribe [:self/user-id])
    (subscribe [::labels])
    (subscribe [:article/labels article-id])])
 (fn [[user-id ui-labels article-labels] [_ article-id label-id]]
   (let [ui-vals (get-in ui-labels [article-id] {})
         article-vals (->> (get-in article-labels [user-id] {})
                           (map-values :answer))]
     (cond-> (merge article-vals ui-vals)
       label-id (get label-id)))))

(reg-sub-raw
 :review/inconsistent-labels
 (fn [db [_ article-id label-id]]
   (reaction
    (let [label-ids @(subscribe [:project/label-ids])
          values @(subscribe [:review/active-labels article-id])
          overall-id @(subscribe [:project/overall-label-id])
          overall-val (some->> overall-id (get values))
          inconsistent
          (when (true? overall-val)
            (->> label-ids
                 (filter
                  (fn [label-id]
                    (let [label-val (get values label-id)
                          inclusion @(subscribe [:label/answer-inclusion
                                                 label-id label-val])]
                      (false? inclusion))))
                 (#(if (empty? %) % (conj % overall-id)))))]
      (if label-id
        (boolean (in? inconsistent label-id))
        (vec inconsistent))))))
