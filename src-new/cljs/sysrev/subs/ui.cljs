(ns sysrev.subs.ui
  (:require
   [reagent.ratom :refer [reaction]]
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub reg-sub-raw]]
   [sysrev.base :refer [active-route]]
   [sysrev.shared.util :refer [in?]]
   [sysrev.subs.core :refer [not-found-value try-get]]))

(defn active-panel [db]
  (get-in db [:state :active-panel]))

(reg-sub :active-panel active-panel)

(defn get-login-redirect-url [db]
  (or (:login-redirect db)
      (let [panel (active-panel db)]
        (if (in? [[:login] [:register]] panel)
          "/" @active-route))))
(reg-sub :login-redirect-url get-login-redirect-url)

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

(defn get-panel-field [db path & [panel]]
  (let [panel (or panel (active-panel db))
        path (if (sequential? path) path [path])]
    (get-in db (concat [:state :panels panel] path) )))

(reg-sub
 :panel-field
 (fn [[_ path & [panel]]]
   [(subscribe [::panel-state panel])])
 (fn [[pstate] [_ path & [panel]]]
   (when (and pstate path)
     (let [path (if (sequential? path) path [path])]
       (get-in pstate path)))))

(defn get-view-field [db path view]
  (let [panel (active-panel db)
        path (if (sequential? path) path [path])]
    (get-in db (concat [:state :panels panel :views view] path))))

(reg-sub
 :view-field
 (fn [[_ view path]]
   [(subscribe [::view-state view])])
 (fn [[vstate] [_ view path]]
   (when (and vstate path)
     (let [path (if (sequential? path) path [path])]
       (get-in vstate path)))))
