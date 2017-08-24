(ns sysrev.events.users
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx dispatch trim-v]]))

(defn- store-user-map [db umap]
  (let [{:keys [user-id]} umap]
    (assert (and umap user-id))
    (assoc-in db [:data :users user-id] umap)))

(reg-event-db
 :user/store
 [trim-v]
 (fn [db [umap]]
   (cond-> db
     umap (store-user-map umap))))

(reg-event-db
 :user/store-multi
 [trim-v]
 (fn [db [umaps]]
   ((->> umaps
         (map (fn [umap] #(store-user-map % umap)))
         (apply comp))
    db)))
