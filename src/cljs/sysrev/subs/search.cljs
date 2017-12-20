(ns sysrev.subs.search
  (:require [re-frame.core :as re-frame :refer
             [reg-sub]]))

(reg-sub
 :pubmed/search-term-result
 (fn [db [_ search-term]]
   (-> db :data :pubmed-search (get-in [search-term]))))
