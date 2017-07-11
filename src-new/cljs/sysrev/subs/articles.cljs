(ns sysrev.subs.articles
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 :articles/all
 (fn [db]
   (get-in db [:data :articles])))

(reg-sub
 :article/raw
 :<- [:articles/all]
 (fn [articles [_ article-id]]
   (get articles article-id)))
