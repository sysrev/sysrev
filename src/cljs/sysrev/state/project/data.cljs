(ns sysrev.state.project.data
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.core :refer [store-user-maps]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.shared.transit :as sr-transit]
            [sysrev.util :refer [dissoc-in]]))

(defn project-loaded? [db project-id]
  (contains? (get-in db [:data :project]) project-id))

(defn- load-project [db {:keys [project-id] :as project}]
  (update-in db [:data :project project-id]
             #(merge % project)))

(def-data :project
  :loaded? project-loaded?
  :uri (fn [project-id] "/api/project-info")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [project users]}]
    (let [url-ids-map
          (->> (:url-ids project)
               (map (fn [{:keys [url-id]}]
                      [url-id project-id]))
               (apply concat)
               (apply hash-map))
          active? (= project-id (active-project-id db))]
      (cond->
          {:db (-> db
                   (load-project (merge project {:error nil}))
                   (store-user-maps (vals users)))
           :dispatch [:load-project-url-ids url-ids-map]}
        active? (merge {:set-page-title (:name project)}))))
  :on-error
  (fn [{:keys [db error]} [project-id] _]
    {:db (-> db (load-project {:project-id project-id
                               :error error}))}))

(def-data :project/settings
  :loaded? project-loaded?
  :uri (fn [project-id] "/api/project-settings")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [settings]}]
    {:db (assoc-in db [:data :project project-id :settings] settings)}))

(def-data :project/files
  :loaded? project-loaded?
  :uri (fn [project-id] (str "/api/files/" project-id))
  :prereqs (fn [project-id] [[:identity]])
  :process
  (fn [{:keys [db]} [project-id] files]
    (when (vector? files)
      {:db (assoc-in db [:data :project project-id :files] files)})))

#_
(def-data :project/public-labels
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :public-labels)))
  :uri (fn [project-id] "/api/public-labels")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] result]
    {:db (assoc-in db [:data :project project-id :public-labels]
                   (sr-transit/decode-public-labels result))}))

(def-data :project/article-list
  :loaded? (fn [db project-id args]
             (-> (get-in db [:data :project project-id :article-list])
                 (contains? args)))
  :uri (fn [project-id] "/api/project-articles")
  :content (fn [project-id args]
             (merge {:project-id project-id}
                    args))
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [{:keys [db]} [project-id args] result]
    {:db (assoc-in db [:data :project project-id :article-list args]
                   result)}))

(reg-sub
 :project/article-list
 (fn [[_ project-id args]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ project-id args]]
   (get-in project [:article-list args])))

(def-data :project/sources
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :sources)))
  :uri (fn [project-id] "/api/project-sources")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process (fn [{:keys [db]} [project-id] {:keys [sources]}]
             {:db (assoc-in db [:data :project project-id :sources]
                            sources)}))

(def-data :member/articles
  :loaded? (fn [db project-id user-id]
             (-> (get-in db [:data :project project-id :member-articles])
                 (contains? user-id)))
  :uri (fn [project-id user-id] (str "/api/member-articles/" user-id))
  :content (fn [project-id user-id] {:project-id project-id})
  :prereqs (fn [project-id user-id] [[:identity] [:project project-id]])
  :process
  (fn [{:keys [db]} [project-id user-id] result]
    {:db (assoc-in db [:data :project project-id :member-articles user-id]
                   (sr-transit/decode-member-articles result))}))

(reg-event-db
 :project/clear-data
 [trim-v]
 (fn [db]
   (if-let [project-id (active-project-id db)]
     (dissoc-in db [:data :project project-id])
     db)))

(reg-sub
 :project/has-articles?
 (fn [[_ project-id]]
   [(subscribe [:project/article-counts project-id])])
 (fn [[{:keys [total]}]]
   (when total (> total 0))))
