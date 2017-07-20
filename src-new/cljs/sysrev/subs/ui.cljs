(ns sysrev.subs.ui
  (:require
   [reagent.ratom :refer [reaction]]
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub reg-sub-raw]]
   [sysrev.subs.core :refer [not-found-value try-get]]))

(defn active-panel [db]
  (get-in db [:state :active-panel]))

(reg-sub :active-panel active-panel)

(reg-sub
 ::panels
 (fn [db]
   (get-in db [:state :panels])))

(reg-sub
 ::panel-state
 (fn [_]
   [(subscribe [::panels])
    (subscribe [:active-panel])])
 (fn [[panels active-panel] [_ panel]]
   (when-let [panel (or panel active-panel)]
     (get panels panel))))

(reg-sub
 ::view-state
 (fn [_]
   [(subscribe [::panel-state])])
 (fn [[pstate] [_ view]]
   (when (and pstate view)
     (get-in pstate [:views view]))))

(reg-sub
 :panel-field
 (fn [[_ path & [panel]]]
   [(subscribe [::panel-state panel])])
 (fn [[pstate] [_ path & [panel]]]
   (when (and pstate path)
     (let [path (if (sequential? path) path [path])]
       (get-in pstate path)))))

(reg-sub
 :view-field
 (fn [[_ view path]]
   [(subscribe [::view-state view])])
 (fn [[vstate] [_ view path]]
   (when (and vstate path)
     (let [path (if (sequential? path) path [path])]
       (get-in vstate path)))))
