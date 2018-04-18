(ns sysrev.events.project
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.subs.project :refer [active-project-id]]
   [sysrev.routes :as routes]
   [sysrev.shared.util :refer [parse-integer integer-project-id?]]))

(reg-event-fx
 :set-active-project-url
 [trim-v]
 (fn [{:keys [db]} [url-id]]
   (let [literal-id (-> (and url-id
                             (integer-project-id? url-id)
                             (parse-integer url-id))
                        (#(if (integer? %) % nil)))
         recent-url (get-in db [:state :recent-project-url])
         cur-active (active-project-id db)
         new-db
         (cond->
             (-> db
                 (assoc-in [:state :active-project-literal] literal-id)
                 (assoc-in [:state :active-project-url] url-id))
             url-id (assoc-in [:state :recent-project-url] url-id))
         new-active (active-project-id new-db)
         ;; Reset data if this causes changing to a new active project.
         changed? (and recent-url url-id
                       (not= recent-url url-id)
                       (not= cur-active new-active))]
     (cond-> {:db new-db}
       changed?
       (merge {:reset-data true})

       (and url-id (nil? literal-id))
       (merge {:dispatch [:require [:project-url-id url-id]]})))))

(reg-event-db
 :project/load
 [trim-v]
 (fn [db [{:keys [project-id] :as pmap}]]
   (update-in db [:data :project project-id]
              #(merge % pmap))))

(reg-event-db
 :project/load-settings
 [trim-v]
 (fn [db [project-id settings]]
   (assoc-in db [:data :project project-id :settings] settings)))

(reg-event-db
 :project/load-sources
 [trim-v]
 (fn [db [project-id sources]]
   (assoc-in db [:data :project project-id :sources] sources)))

(reg-event-db
 :project/load-important-terms
 [trim-v]
 (fn [db [project-id terms]]
   (assoc-in db [:data :project project-id :importance] terms)))

(reg-event-db
 :project/load-files
 [trim-v]
 (fn [db [project-id files]]
   (assoc-in db [:data :project project-id :files] files)))

(reg-event-db
 :project/clear-data
 [trim-v]
 (fn [db]
   (if-let [project-id (active-project-id db)]
     (dissoc-in db [:data :project project-id])
     db)))

(reg-event-db
 :project/load-public-labels
 [trim-v]
 (fn [db [project-id content]]
   (assoc-in db [:data :project project-id :public-labels]
             content)))

(reg-event-fx
 :project/navigate
 [trim-v]
 (fn [_ [project-id]]
   {:nav-scroll-top (routes/project-uri project-id "")}))

(reg-event-db
 :load-project-url-ids
 [trim-v]
 (fn [db [url-ids-map]]
   (update-in db [:data :project-url-ids]
              #(merge % url-ids-map))))

(reg-event-db
 :project/load-prediction-histograms
 [trim-v]
 (fn [db [project-id content]]
   (assoc-in db [:data :project project-id :histograms] content)))
