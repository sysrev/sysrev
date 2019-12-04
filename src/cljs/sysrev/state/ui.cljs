(ns sysrev.state.ui
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db trim-v]]
            [sysrev.state.nav :as nav]
            [sysrev.shared.util :refer [dissoc-in]]))

;;;
;;; Panels
;;;
;;; A "panel" is a top-level component for a route, identified by a panel
;;; value which is a vector of keywords. These functions provide for reading
;;; and writing state associated with a specific panel.
;;;

(reg-sub ::panels #(get-in % [:state :panels]))

(reg-sub ::panel-state
         :<- [::panels]
         :<- [:active-panel]
         (fn [[panels active-panel] [_ panel]]
           (when-let [panel (or panel active-panel)]
             (get panels panel))))

(defn get-panel-field [db path & [panel]]
  (let [panel (or panel (nav/active-panel db))
        path (if (or (nil? path) (sequential? path))
               path [path])]
    (get-in db (concat [:state :panels panel] path))))

(reg-sub :panel-field
         (fn [[_ _path & [panel]]] (subscribe [::panel-state panel]))
         (fn [pstate [_ path & [_panel]]]
           (when (and pstate path)
             (let [path (if (or (nil? path) (sequential? path))
                          path [path])]
               (get-in pstate path)))))

(defn set-panel-field [db path val & [panel]]
  (let [panel (or panel (nav/active-panel db))
        path (if (or (nil? path) (sequential? path))
               path [path])]
    (assoc-in db (concat [:state :panels panel] path) val)))

(reg-event-db :set-panel-field [trim-v]
              (fn [db [path val & [panel]]]
                (set-panel-field db path val panel)))

(reg-event-db :reset-transient-fields [trim-v]
              (fn [db [panel]]
                (dissoc-in db [:state :panels panel :transient])))

;;;
;;; Views
;;;
;;; A "view" is a subpath of panel state, for keeping state associated
;;; with some element that may appear in multiple panels, and whose
;;; state should be separate in each.
;;;

(reg-sub ::view-state
         (fn [[_ _view & [panel]]] (subscribe [::panel-state panel]))
         (fn [pstate [_ view & [_panel]]]
           (when (and pstate view)
             (get-in pstate [:views view]))))

(defn get-view-field [db view path & [panel]]
  (let [panel (or panel (nav/active-panel db))
        path (if (or (nil? path) (sequential? path))
               path [path])]
    (get-in db (concat [:state :panels panel :views view] path))))

(defn set-view-field [db view path val & [panel]]
  (let [panel (or panel (nav/active-panel db))]
    (assoc-in db (concat [:state :panels panel :views view] path) val)))

(defn update-view-field [db view path update-fn & [panel]]
  (let [panel (or panel (nav/active-panel db))]
    (update-in db (concat [:state :panels panel :views view] path) update-fn)))

(reg-sub :view-field
         (fn [[_ view _path & [panel]]] (subscribe [::view-state view panel]))
         (fn [vstate [_ _view path & _]]
           (when (and vstate path)
             (let [path (if (or (nil? path) (sequential? path))
                          path [path])]
               (get-in vstate path)))))

(reg-event-db :set-view-field [trim-v]
              (fn [db [view path val & [panel]]]
                (let [panel (or panel (nav/active-panel db))]
                  (assoc-in db (concat [:state :panels panel :views view] path) val))))

;;;
;;; Misc
;;;

(reg-sub :visible-article-id
         :<- [:review/on-review-task?]
         :<- [:review/task-id]
         :<- [:project-articles/article-id]
         :<- [:article-view/article-id]
         (fn [[on-review? id-review id-project id-single]]
           (or (and on-review? id-review)
               id-project
               id-single)))
