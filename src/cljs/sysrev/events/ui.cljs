(ns sysrev.events.ui
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx dispatch trim-v]]
   [sysrev.subs.ui :refer
    [active-panel active-subpanel-uri default-subpanel-uri panel-prefixes]]))

(defn set-active-subpanel [db prefix uri]
  (assoc-in db [:state :navigation :subpanels (vec prefix)] uri))

(defn set-subpanel-default-uri [db prefix uri]
  (assoc-in db [:state :navigation :defaults (vec prefix)] uri))

(reg-event-db
 :set-active-subpanel
 [trim-v]
 (fn [db [prefix uri]]
   (set-active-subpanel db prefix uri)))

(reg-event-fx
 :set-active-panel
 [trim-v]
 (fn [{:keys [db]} [panel uri]]
   (let [active (active-panel db)]
     {:db (assoc-in db [:state :active-panel] panel)
      :dispatch-n
      (concat
       [[:review/reset-ui-labels]
        [:review/reset-ui-notes]
        [:reset-transient-fields panel]]
       (->> (panel-prefixes panel)
            (map (fn [prefix]
                   [:set-active-subpanel prefix uri]))))})))

(reg-event-fx
 :navigate
 [trim-v]
 (fn [{:keys [db]} [path & {:keys [scroll-top?]}]]
   (let [active (active-panel db)]
     {(if scroll-top? :nav-scroll-top :nav)
      (if (= path (take (count path) active))
        (default-subpanel-uri db path)
        (active-subpanel-uri db path))})))

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

(reg-event-db
 :reset-transient-fields
 [trim-v]
 (fn [db [panel]]
   (assoc-in db [:state :panels panel :transient] {})))
