(ns sysrev.events.ui
  (:require [re-frame.core :as re-frame :refer
             [reg-event-db reg-event-fx subscribe dispatch trim-v]]))

(reg-event-db
 :set-active-panel
 [trim-v]
 (fn [db [panel]]
   (let [active @(subscribe [:active-panel])]
     (assoc-in db [:state :active-panel] panel))))

(reg-event-db
 :set-panel-field
 [trim-v]
 (fn [db [path val & [panel]]]
   (let [panel (or panel @(subscribe [:active-panel]))]
     (assoc-in db (concat [:state :panels panel] path) val))))

(reg-event-db
 :set-view-field
 [trim-v]
 (fn [db [view path val & [panel]]]
   (let [panel (or panel @(subscribe [:active-panel]))]
     (assoc-in db (concat [:state :panels panel :views view] path) val))))
