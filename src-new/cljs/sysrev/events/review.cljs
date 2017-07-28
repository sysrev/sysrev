(ns sysrev.events.review
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v reg-fx]]
   [sysrev.subs.labels :as labels]
   [sysrev.subs.review :as review]))

(reg-event-db
 :review/load-task
 [trim-v]
 (fn [db [article-id today-count]]
   (-> db
       (assoc-in [:data :review :task-id] article-id)
       (assoc-in [:data :review :today-count] today-count))))

(reg-event-fx
 :review/send-labels
 [trim-v]
 (fn [{:keys [db]} [{:keys [article-id confirm? resolve?]}]]
   (let [label-values (review/active-labels db article-id)]
     {:dispatch [:action [:send-labels {:article-id article-id
                                        :label-values label-values
                                        :confirm? confirm?
                                        :resolve? resolve?}]]})))
