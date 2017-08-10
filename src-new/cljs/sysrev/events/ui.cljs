(ns sysrev.events.ui
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx dispatch trim-v]]
   [sysrev.subs.ui :refer [active-panel]]))

(reg-event-fx
 :set-active-panel
 [trim-v]
 (fn [{:keys [db]} [panel]]
   (let [active (active-panel db)]
     {:db (assoc-in db [:state :active-panel] panel)
      :dispatch [:review/reset-ui-labels]})))

(reg-event-db
 :set-panel-field
 [trim-v]
 (fn [db [path val & [panel]]]
   (let [panel (or panel (active-panel db))]
     (assoc-in db (concat [:state :panels panel] path) val))))

(reg-event-db
 :set-view-field
 [trim-v]
 (fn [db [view path val & [panel]]]
   (let [panel (or panel (active-panel db))]
     (assoc-in db (concat [:state :panels panel :views view] path) val))))
