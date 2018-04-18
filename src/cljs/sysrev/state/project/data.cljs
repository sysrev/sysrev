(ns sysrev.state.project.data
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.util :refer [dissoc-in]]))

(defn project-loaded? [db project-id]
  (contains? (get-in db [:data :project]) project-id))

(defn project-sources-loaded? [db project-id]
  (contains? (get-in db [:data :project project-id]) :sources))

(defn project-important-terms-loaded? [db project-id]
  (contains? (get-in db [:data :project project-id]) :importance))

(defn have-public-labels? [db project-id]
  (let [project (get-project-raw db project-id)]
    (contains? project :public-labels)))

(defn have-member-articles? [db project-id user-id]
  (let [project (get-project-raw db project-id)]
    (contains? (:member-articles project) user-id)))

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

(reg-event-db
 :member/load-articles
 [trim-v]
 (fn [db [user-id articles]]
   (let [project-id (active-project-id db)]
     (assoc-in db [:data :project project-id :member-articles user-id]
               articles))))

(reg-sub
 :project/has-articles?
 (fn [[_ project-id]]
   [(subscribe [:project/article-counts project-id])])
 (fn [[{:keys [total]}]]
   (when total (> total 0))))

(reg-sub
 :project/important-terms
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ entity-type project-id]]
   (if (nil? entity-type)
     (get-in project [:importance :terms])
     (get-in project [:importance :terms entity-type]))))

(reg-sub
 :project/important-terms-loading?
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ entity-type project-id]]
   (true? (get-in project [:importance :loading]))))
