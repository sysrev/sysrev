(ns sysrev.state.project.data
  (:require [re-frame.core :refer [subscribe reg-sub reg-event-db dispatch trim-v]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.state.core :refer [store-user-maps]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.article-list.base :as al]
            [sysrev.util :as util :refer [index-by dissoc-in]]))

(defn project-loaded? [db project-id]
  (contains? (get-in db [:data :project]) project-id))

(defn- load-project [db {:keys [project-id] :as project}]
  (update-in db [:data :project project-id]
             ;; avoid wiping out data from :project/review-status
             (fn [current]
               (merge current project
                      (when-let [stats (not-empty (merge (:stats current)
                                                         (:stats project)))]
                        {:stats stats})))))

(def-data :public-projects
  :uri     "/api/public-projects"
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :public-projects)))
  :process (fn [{:keys [db]} [] {:keys [projects]}]
             {:db (assoc-in db [:data :public-projects]
                            (index-by :project-id projects))}))

(reg-sub :public-projects
         (fn [db [_ project-id]]
           (cond-> (get-in db [:data :public-projects])
             project-id (get project-id))))

(reg-sub :public-project-ids
         :<- [:public-projects]
         #(sort (map :project-id (vals %))))

(def-data :project
  :loaded? project-loaded?
  :uri (constantly "/api/project-info")
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
  :uri (constantly "/api/project-settings")
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
  :uri (constantly "/api/project-articles")
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
  :uri (constantly "/api/project-articles")
  :content (fn [project-id args]
             (merge {:project-id project-id} args))
  :prereqs (fn [project-id args]
             [[:project project-id]
              [:project/article-list-count project-id (dissoc args :n-count :n-offset)]])
  :process
  (fn [{:keys [db]} [project-id args] result]
    (doseq [panel [[:project :project :articles]
                   [:project :project :articles-data]]]
      (let [context {:panel panel}
            action @(subscribe [::al/get context [:recent-nav-action]])]
        (js/setTimeout
         (fn []
           (when-not (data/loading? #{:project/article-list
                                      :project/article-list-count})
             (dispatch [::al/set-recent-nav-action context nil])))
         (case action
           :transition  150
           :refresh     75
           50))))
    {:db (assoc-in db [:data :project project-id :article-list args]
                   result)}))

(reg-sub :project/article-list
         (fn [[_ project-id _]] (subscribe [:project/raw project-id]))
         (fn [project [_ _ args]]
           (get-in project [:article-list args])))

(reg-sub :project/article-list-count
         (fn [[_ project-id _]] (subscribe [:project/raw project-id]))
         (fn [project [_ _ args]]
           (get-in project [:article-list-count args])))

(def-data :project/sources
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :sources)))
  :uri     "/api/project-sources"
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] {:keys [sources]}]
             {:db (assoc-in db [:data :project project-id :sources] sources)}))

(def-data :project-source/sample-article
  :loaded? (fn [db project-id source-id]
             (-> (get-in db [:data :project project-id :sample-article])
                 (contains? source-id)))
  :uri (fn [_ source-id] (str "/api/sources/" source-id "/sample-article"))
  :content (fn [project-id _] {:project-id project-id})
  :process (fn [{:keys [db]} [project-id source-id] {:keys [article]}]
             {:db (assoc-in db [:data :project project-id :sample-article source-id] article)}))

(reg-sub :project-source/sample-article
         (fn [db [_ project-id source-id] _]
           (get-in db [:data :project project-id :sample-article source-id])))

(reg-event-db :project/clear-data [trim-v]
              #(if-let [project-id (active-project-id %)]
                 (dissoc-in % [:data :project project-id])
                 %))

(reg-sub :project/has-articles?
         (fn [[_ project-id]] (subscribe [:project/article-counts project-id]))
         (fn [{:keys [total]}] (some-> total pos?)))
