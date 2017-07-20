(ns sysrev.events.review
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe trim-v]]
   [sysrev.subs.review :as review]))

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
 (fn [{:keys [db]} [article-id label-id value]]
   ;; TODO: fix to support multiple values
   {:db (assoc-in db [:state :review :labels article-id label-id]
                  value)}))

(reg-event-fx
 :review/set-label-value
 [trim-v]
 (fn [{:keys [db]} [article-id label-id value]]
   {:db (assoc-in db [:state :review :labels article-id label-id]
                  value)}))
