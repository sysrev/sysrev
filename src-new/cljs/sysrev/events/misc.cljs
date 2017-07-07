(ns sysrev.events.misc
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe trim-v reg-fx]]
   [sysrev.base :as base :refer [ga-event]]
   [sysrev.util :refer [scroll-top]]
   [sysrev.routes :refer [nav nav-scroll-top force-dispatch]]))

(reg-event-db
 :initialize-db
 (fn [_]
   base/default-db))

(reg-event-db
 :set-csrf-token
 [trim-v]
 (fn [db [csrf-token]]
   (assoc db :csrf-token csrf-token)))

(reg-event-db
 :schedule-scroll-top
 (fn [db]
   (assoc db :scroll-top true)))

(reg-event-db
 :scroll-top
 (fn [db]
   (scroll-top)
   (dissoc db :scroll-top)))

(reg-event-db
 :ga-event
 [trim-v]
 (fn [db [category action & [label]]]
   (ga-event category action label)
   db))

(defn- schedule-notify-display []
  (when-let [{:keys [display-ms] :as entry}
             @(subscribe [:active-notification])]
    (js/setTimeout #(do #_ something)
                   display-ms)))

(reg-event-db
 :notify
 [trim-v]
 (fn [db [message & [{:keys [class display-ms]
                      :or {class "blue" display-ms 1500}
                      :as options}]]]
   (let [entry {:message message
                :class class
                :display-ms display-ms}
         inactive? nil #_ (empty? (visible-notifications))]
     #_ (add-notify-entry)
     (when inactive?
       (schedule-notify-display)))))
