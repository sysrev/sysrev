(ns sysrev.subs.review
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 :review/article-id
 (fn [db]
   (get-in db [:data :review :article-id])))

(reg-sub
 :review/today-count
 (fn [db]
   (get-in db [:data :review :today-count])))
