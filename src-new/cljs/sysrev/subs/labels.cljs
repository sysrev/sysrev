(ns sysrev.subs.labels
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 ::labels
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:labels project)))

(reg-sub
 ::label
 (fn [[_ label-id project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels] [_ label-id]]
   (get labels label-id)))

(reg-sub
 :project/label-ids
 (fn [[_ project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels]]
   ;; TODO: sort by display order
   (keys labels)))

(reg-sub
 :project/have-label?
 (fn [[_ label-id project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels] [_ label-id]]
   (if (contains? labels label-id) true nil)))

(reg-sub
 :project/overall-label-id
 (fn [[_ project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels]]
   (->> (keys labels)
        (filter (fn [label-id]
                  (let [{:keys [name]} (get labels label-id)]
                    (= name "overall include"))))
        first)))

(reg-sub
 :label/display
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]]
   (or (:short-label label)
       (:name label))))

(reg-sub
 ::definition
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:definition label)))

(reg-sub
 :label/value-type
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:value-type label)))
(reg-sub
 :label/boolean?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])])
 (fn [[value-type]] (= value-type "boolean")))
(reg-sub
 :label/categorical?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])])
 (fn [[value-type]] (= value-type "categorical")))
(reg-sub
 :label/string?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])])
 (fn [[value-type]] (= value-type "string")))

(reg-sub
 :label/all-values
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])
    (subscribe [::definition label-id project-id])])
 (fn [[value-type definition]]
   (case value-type
     "boolean" [true false]
     "categorical" (:all-values definition)
     nil)))
