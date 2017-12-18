(ns sysrev.events.search
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v]]
   [sysrev.subs.project :as project]
   [sysrev.subs.auth :as auth]))

(reg-event-db
 :pubmed/save-search-term-results
 [trim-v]
 (fn [db [search-term search-term-response]]
   (assoc-in db [:data :search-term search-term]
             search-term-response)))
