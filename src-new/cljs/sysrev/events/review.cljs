(ns sysrev.events.review
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe trim-v]]))

(reg-event-db
 :review/load-task
 [trim-v]
 (fn [db [article-id today-count]]
   (-> db
       (assoc-in [:data :review :task-id] article-id)
       (assoc-in [:data :review :today-count] today-count))))

(reg-event-fx
 :review/enable-label-value
 [trim-v]
 (fn [{:keys [db]} [label-id label-value]]
   {:db db}))
