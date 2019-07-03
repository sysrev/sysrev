(ns sysrev.state.project.data
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]
            [sysrev.loading :as loading]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.core :refer [store-user-maps]]
            [sysrev.state.nav :refer [active-project-id active-panel]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.views.panels.project.articles :as project-articles]
            [sysrev.views.article-list.base :as al]
            [sysrev.shared.transit :as sr-transit]
            [sysrev.util :refer [dissoc-in]]
            [sysrev.shared.util :as sutil :refer [in? ->map-with-key]]))

(defn project-loaded? [db project-id]
  (contains? (get-in db [:data :project]) project-id))

(defn- load-project [db {:keys [project-id] :as project}]
  (update-in db [:data :project project-id]
             #(merge % project)))

(def-data :public-projects
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :public-projects)))
  :uri (fn [] "/api/public-projects")
  :process (fn [{:keys [db]} [] {:keys [projects]}]
             {:db (assoc-in db [:data :public-projects]
                            (->map-with-key :project-id projects))}))

(def-data :project
  :loaded? project-loaded?
  :uri (fn [project-id] "/api/project-info")
  :content (fn [project-id] {:project-id project-id})
  :process (fn [{:keys [db]} [project-id] {:keys [project users]}]
             (let [url-ids-map (->> (:url-ids project)
                                    (map (fn [{:keys [url-id]}] {url-id project-id}))
                                    (apply merge))
                   active? (= project-id (active-project-id db))]
               (cond-> {:db (-> (load-project db (merge project {:error nil}))
                                (store-user-maps (vals users)))
                        :dispatch [:load-project-url-ids url-ids-map]}
                 active? (merge {:set-page-title (:name project)}))))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:db (load-project db {:project-id project-id :error error})}))

(def-data :project/settings
  :loaded? project-loaded?
  :uri (fn [project-id] "/api/project-settings")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] {:keys [settings project-name]}]
    {:db (cond-> db
           settings      (assoc-in [:data :project project-id :settings] settings)
           project-name  (assoc-in [:data :project project-id :name] project-name))}))

(def-data :project/files
  :loaded? project-loaded?
  :uri (fn [project-id] (str "/api/files/" project-id))
  :prereqs (fn [project-id] [[:project project-id]])
  :process
  (fn [{:keys [db]} [project-id] files]
    (when (vector? files)
      {:db (assoc-in db [:data :project project-id :files] files)})))

(def-data :project/article-list-count
  :method :post
  :loaded? (fn [db project-id args]
             (-> (get-in db [:data :project project-id :article-list-count])
                 (contains? args)))
  :uri (fn [project-id] "/api/project-articles")
  :content (fn [project-id args]
             (merge {:project-id project-id}
                    args
                    {:lookup-count true}))
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id args] result]
             {:db (assoc-in db [:data :project project-id :article-list-count args]
                            result)}))

(def-data :project/article-list
  :method :post
  :loaded? (fn [db project-id args]
             (-> (get-in db [:data :project project-id :article-list])
                 (contains? args)))
  :uri (fn [project-id] "/api/project-articles")
  :content (fn [project-id args]
             (merge {:project-id project-id} args))
  :prereqs (fn [project-id args]
             [[:project project-id]
              [:project/article-list-count project-id (dissoc args :n-count :n-offset)]])
  :process
  (fn [{:keys [db]} [project-id args] result]
    (doseq [panel [[:project :project :articles]]]
      (let [context {:panel panel}
            action @(subscribe [::al/get context [:recent-nav-action]])]
        (js/setTimeout
         (fn []
           (when-not (some #(loading/any-loading? :only %)
                           [:project/article-list :project/article-list-count])
             (dispatch [::al/set-recent-nav-action context nil])))
         (case action
           :transition  150
           :refresh     75
           50))))
    {:db (assoc-in db [:data :project project-id :article-list args]
                   result)}))

(reg-sub
 :project/article-list
 (fn [[_ project-id args]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ project-id args]]
   (get-in project [:article-list args])))

(reg-sub
 :project/article-list-count
 (fn [[_ project-id args]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ project-id args]]
   (get-in project [:article-list-count args])))

(def-data :project/sources
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :sources)))
  :uri (fn [project-id] "/api/project-sources")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] {:keys [sources]}]
             {:db (assoc-in db [:data :project project-id :sources] sources)}))

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

(reg-sub
 :public-projects
 (fn [db [_ project-id]]
   (cond-> (get-in db [:data :public-projects])
     project-id (get project-id))))

(reg-sub
 :public-project-ids
 (fn [[]]
   [(subscribe [:public-projects])])
 (fn [[projects]]
   (->> (vals projects)
        (sort-by :project-id <)
        (map :project-id))))
