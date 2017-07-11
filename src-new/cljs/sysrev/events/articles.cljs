(ns sysrev.events.articles
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe trim-v]]))

(reg-event-db
 :articles/clear-all
 [trim-v]
 (fn [db]
   (assoc-in db [:data :articles] {})))

(reg-event-db
 :article/load
 [trim-v]
 (fn [db [{:keys [article-id] :as article}]]
   (assoc-in db [:data :articles article-id] article)))

(reg-event-db
 :articles/load
 [trim-v]
 (fn [db [articles]]
   (update-in db [:data :articles] #(merge % articles))))
