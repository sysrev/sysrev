(ns sysrev.state.core
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]
            [sysrev.base :as base :refer [ga-event]]
            [sysrev.nav :refer [nav-scroll-top force-dispatch]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :refer [dissoc-in]]))

(reg-event-db
 :initialize-db
 (fn [_]
   base/default-db))

(reg-event-fx
 :reset-data
 (fn [{:keys [db]}]
   {:db (-> db
            (assoc :data {}
                   :needed [])
            (dissoc-in [:state :self])
            (dissoc-in [:state :review])
            (dissoc-in [:state :panels])
            (dissoc-in [:state :navigation :subpanels]))
    :dispatch-n (list [:require [:identity]]
                      [:reload [:identity]])
    :fetch-missing [true nil]}))

(reg-fx
 :reset-data
 (fn [reset?] (when reset? (dispatch [:reset-data]))))

(reg-event-fx
 :reset-ui
 (fn [{:keys [db]}]
   {:db (-> db
            (dissoc-in [:data :review])
            (dissoc-in [:state :review])
            (dissoc-in [:state :panels])
            (dissoc-in [:state :navigation :subpanels]))}))

(reg-fx
 :reset-ui
 (fn [reset?] (when reset? (dispatch [:reset-ui]))))

(reg-event-fx
 :reset-needed
 (fn [{:keys [db]}]
   {:db (-> db (assoc :needed []))
    :dispatch [:require [:identity]]}))

(reg-fx
 :reset-needed
 (fn [reset?] (when reset? (dispatch [:reset-needed]))))

(reg-sub
 :initialized?
 :<- [:app-id]
 :<- [:have-identity?]
 :<- [:active-panel]
 (fn [[app-id have-identity? active-panel]]
   (case app-id
     :blog true
     (boolean (and have-identity? active-panel)))))

(reg-event-db
 :ga-event
 [trim-v]
 (fn [db [category action & [label]]]
   (ga-event category action label)
   db))

(defn store-user-map [db umap]
  (let [{:keys [user-id]} umap]
    (assert (and umap user-id))
    (assoc-in db [:data :users user-id] umap)))

(defn store-user-maps [db umaps]
  ((->> umaps
        (map (fn [umap] #(store-user-map % umap)))
        (apply comp))
   db))

(def-action :dev/clear-query-cache
  :uri (fn [] "/api/clear-query-cache")
  :process
  (fn [_ _ result]
    {:reset-data true}))
