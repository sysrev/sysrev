(ns sysrev.state.core
  (:require [sysrev.base :as base :refer [ga-event]]
            [sysrev.nav :refer [nav-scroll-top force-dispatch]]
            [sysrev.util :refer [dissoc-in]]
            [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]))

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
 :set-build-id
 [trim-v]
 (fn [db [build-id]]
   (assoc db :build-id build-id)))

(reg-event-db
 :set-build-time
 [trim-v]
 (fn [db [build-time]]
   (assoc db :build-time build-time)))

(reg-event-fx
 :reset-data
 (fn [{:keys [db]}]
   {:db (-> db
            (assoc :data {}
                   :needed [])
            (dissoc-in [:state :review])
            (dissoc-in [:state :panels]))
    :dispatch [:require [:identity]]
    :fetch-missing true}))

(reg-fx
 :reset-data
 (fn [reset?] (when reset? (dispatch [:reset-data]))))

(defn get-csrf-token [db] (:csrf-token db))
(reg-sub :csrf-token get-csrf-token)

(reg-sub
 :initialized?
 :<- [:have-identity?]
 :<- [:active-panel]
 (fn [[have-identity? active-panel]]
   (boolean (and have-identity? active-panel))))

(defn get-build-id [db] (:build-id db))
(reg-sub :build-id get-build-id)
(defn get-build-time [db] (:build-time db))
(reg-sub :build-time get-build-time)

(reg-event-db
 :ga-event
 [trim-v]
 (fn [db [category action & [label]]]
   (ga-event category action label)
   db))
