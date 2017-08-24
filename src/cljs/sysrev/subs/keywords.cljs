(ns sysrev.subs.keywords
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 :project/keywords
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:keywords project)))
