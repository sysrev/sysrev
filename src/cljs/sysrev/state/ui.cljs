(ns sysrev.state.ui
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx dispatch trim-v]]
            [sysrev.base :refer [use-new-article-list?]]
            [sysrev.state.nav :as nav]
            [sysrev.util :refer [dissoc-in]]))

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
 (fn [[_ view & [panel]]]
   [(subscribe [::panel-state panel])])
 (fn [[pstate] [_ view & [panel]]]
   (when (and pstate view)
     (get-in pstate [:views view]))))

(defn get-panel-field [db path & [panel]]
  (let [panel (or panel (nav/active-panel db))
        path (if (or (nil? path) (sequential? path))
               path [path])]
    (get-in db (concat [:state :panels panel] path))))

(reg-sub
 :panel-field
 (fn [[_ path & [panel]]]
   [(subscribe [::panel-state panel])])
 (fn [[pstate] [_ path & [panel]]]
   (when (and pstate path)
     (let [path (if (or (nil? path) (sequential? path))
                  path [path])]
       (get-in pstate path)))))

(defn get-view-field [db path view & [panel]]
  (let [panel (or panel (nav/active-panel db))
        path (if (or (nil? path) (sequential? path))
               path [path])]
    (get-in db (concat [:state :panels panel :views view] path))))

(reg-sub
 :view-field
 (fn [[_ view path & [panel]]]
   [(subscribe [::view-state view panel])])
 (fn [[vstate] [_ view path _]]
   (when (and vstate path)
     (let [path (if (or (nil? path) (sequential? path))
                  path [path])]
       (get-in vstate path)))))

(defn set-panel-field [db path val & [panel]]
  (let [panel (or panel (nav/active-panel db))]
    (assoc-in db (concat [:state :panels panel] path) val)))

(reg-event-db
 :set-panel-field
 [trim-v]
 (fn [db [path val & [panel]]]
   (set-panel-field db path val panel)))

(defn set-view-field [db view path val & [panel]]
  (let [panel (or panel (nav/active-panel db))]
    (assoc-in db (concat [:state :panels panel :views view] path) val)))

(reg-event-db
 :set-view-field
 [trim-v]
 (fn [db [view path val & [panel]]]
   (let [panel (or panel (nav/active-panel db))]
     (assoc-in db (concat [:state :panels panel :views view] path) val))))

(reg-event-db
 :reset-transient-fields
 [trim-v]
 (fn [db [panel]]
   (dissoc-in db [:state :panels panel :transient])))

;; TODO: re-add support for notification popups?
(reg-sub
 :active-notification
 (fn [db]
   nil))

(defn- schedule-notify-display [entry]
  (when-let [{:keys [display-ms]} entry]
    (js/setTimeout #(do #_ something)
                   display-ms)))

(reg-event-fx
 :notify
 [trim-v]
 (fn [{:keys [db]} [message & {:keys [class display-ms]
                               :or {class "blue" display-ms 1500}
                               :as options}]]
   (let [entry {:message message
                :class class
                :display-ms display-ms}
         inactive? nil #_ (empty? (visible-notifications))]
     #_ (add-notify-entry entry)
     (when inactive?
       (schedule-notify-display entry)))))

(reg-sub
 :visible-article-id
 (fn [_]
   [(subscribe [:review/on-review-task?])
    (subscribe [:review/task-id])
    (subscribe [:public-labels/article-id])
    (subscribe [:user-labels/article-id])
    (subscribe [:project-articles/article-id])])
 (fn [[on-review? id-review id-public id-user id-project]]
   (or (and on-review? id-review)
       (and (not use-new-article-list?) id-public)
       (and (not use-new-article-list?) id-user)
       (and use-new-article-list? id-project))))
