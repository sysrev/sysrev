(ns sysrev.events.review
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe trim-v]]))

(reg-event-fx
 :review/load-task
 [trim-v]
 (fn [{:keys [db]} [{:keys [article-id] :as article}
                    today-count]]
   {:db (-> db
            (assoc-in [:data :review :article-id] article-id)
            (assoc-in [:data :review :today-count] today-count)) 
    :dispatch [:article/load article]}))
