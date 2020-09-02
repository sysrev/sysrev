(ns sysrev.state.core
  (:require [re-frame.core :refer
             [dispatch reg-sub reg-event-db reg-event-fx trim-v reg-fx]]
            [sysrev.base :as base :refer [ga-event]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :as util :refer [dissoc-in]]))

(reg-event-db :initialize-db (constantly base/default-db))

(reg-event-fx :reset-data
              (fn [{:keys [db]}]
                {:db (-> (assoc db :data {} :needed [])
                         (dissoc-in [:state :identity])
                         (dissoc-in [:state :self])
                         (dissoc-in [:state :review])
                         (dissoc-in [:state :panels])
                         (dissoc-in [:state :navigation :subpanels]))
                 :dispatch [:data/load [:identity]]}))

(reg-fx :reset-data
        (fn [reset?] (when reset? (dispatch [:reset-data]))))

(reg-event-db :reset-project-ui
              (fn [db]
                (-> (dissoc-in db [:data :review])
                    (dissoc-in [:state :review])
                    (dissoc-in [:state :navigation :subpanels])
                    (update-in [:state :panels]
                               (fn [panels-map]
                                 (util/filter-keys
                                  #(if (sequential? %)
                                     (not= :project (first %))
                                     ;; panel key should always be a vector, handling
                                     ;; case that it isn't just to be safe
                                     true)
                                  panels-map))))))

(reg-fx :reset-project-ui
        (fn [reset?] (when reset? (dispatch [:reset-project-ui]))))

(reg-event-fx :reset-needed
              (fn [{:keys [db]}]
                {:db (assoc db :needed [])
                 :dispatch [:require [:identity]]}))

(reg-fx :reset-needed
        (fn [reset?] (when reset? (dispatch [:reset-needed]))))

(reg-sub :initialized?
         :<- [:have-identity?] :<- [:active-panel]
         (fn [[have-identity? active-panel]]
           (boolean (and have-identity? active-panel))))

(reg-event-db :ga-event [trim-v]
              (fn [db [category action & [label value]]]
                (ga-event category action label value)
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
  :process (fn [_ _ _] {:reset-data true}))
