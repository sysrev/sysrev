(ns sysrev.views.panels.project.articles-data
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-fx trim-v reg-fx]]
            [sysrev.state.nav :refer [project-uri active-project-id]]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.core :refer [ArticleListPanel]]
            [sysrev.views.article-list.filters :refer [export-type-default-filters]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :articles-data])

(defn- get-context-from-db [db]
  (let [project-id (active-project-id db)]
    {:panel panel
     :base-uri (project-uri project-id "/data")
     :article-base-uri (project-uri project-id "/article")
     :defaults {:filters []}}))

(reg-sub ::article-data-context
         :<- [:project/uri]
         (fn [project-uri]
           {:panel panel
            :base-uri (str project-uri "/data")
            :article-base-uri (str project-uri "/article")
            :defaults {:filters []
                       :display {:show-labels true}}}))

(defn get-context []
  @(subscribe [::article-data-context]))

(reg-sub :project-articles/article-id
         :<- [:active-panel]
         :<- [:article-list/article-id {:panel panel}]
         (fn [[active-panel article-id]]
           (when (= active-panel panel)
             article-id)))

(defn load-settings-and-navigate
  "Loads article list settings and navigates to the page from another panel,
  while maintaining clean browser navigation history for Back/Forward."
  [{:keys [filters display sort-by sort-dir] :as settings}]
  (let [context (get-context)]
    (dispatch [:article-list/load-settings (get-context) settings])
    (dispatch [::al/navigate context :redirect? false])
    (util/scroll-top)))

(defn load-consensus-settings
  "Loads settings corresponding to a consensus category from graphs on overview
  page, and navigates to articles page."
  [& {:keys [status inclusion]}]
  (load-settings-and-navigate
   {:filters [{:consensus {:status status, :inclusion inclusion}}]
    :display {:show-inclusion true
              :show-labels true}
    :sort-by :content-updated
    :sort-dir :desc}))

(defn load-source-filters
  "Loads settings for filtering by article source, and navigates to articles page."
  [& {:keys [source-ids]}]
  (load-settings-and-navigate
   {:filters (->> source-ids (mapv #(do {:source {:source-ids [%]}})))
    :display {:show-inclusion true}
    :sort-by :content-updated
    :sort-dir :desc}))

(reg-fx ::load-source-filters
        (fn [source-ids] (load-source-filters :source-ids source-ids)))

(reg-event-fx :articles/load-source-filters
              (fn [_ [_ source-ids]]
                {::load-source-filters source-ids}))

(defn load-export-settings
  "Loads default settings for file export type, then navigates to
  articles page if navigate is true."
  [export-type navigate]
  (dispatch [:article-list/load-settings (get-context)
             {:filters (get export-type-default-filters export-type)
              :display {:show-inclusion true, :expand-export (name export-type)}
              :sort-by :content-updated
              :sort-dir :desc}])
  (when navigate
    (dispatch [::al/navigate (get-context) :redirect? false])
    (util/scroll-top)))

(reg-fx ::load-export-settings
        (fn [[export-type navigate]] (load-export-settings export-type navigate)))

(reg-event-fx :articles/load-export-settings [trim-v]
              (fn [_ [export-type navigate]]
                {::load-export-settings [export-type navigate]}))

(defn load-member-label-settings
  "Loads settings corresponding to a user's article count from graphs on
  overview page, and navigates to articles page."
  [user-id]
  (let [display {:show-inclusion true
                 :show-labels true
                 :show-notes false}
        filters [{:consensus {:status nil
                              :inclusion nil}}
                 {:has-user {:user user-id
                             :content :labels
                             :confirmed true}}]]
    (load-settings-and-navigate
     {:filters filters
      :display display
      :sort-by :content-updated
      :sort-dir :desc})))

(defn load-label-value-settings
  "Loads settings corresponding to a label value from graphs on overview page,
  and navigates to articles page."
  [label-id value]
  (let [display {:show-inclusion true
                 :show-labels true
                 :show-notes false}
        filters [{:has-label {:label-id label-id
                              :users nil
                              :values [value]
                              :inclusion nil
                              :confirmed true}}]]
    (load-settings-and-navigate
     {:filters filters
      :display display
      :sort-by :content-updated
      :sort-dir :desc})))

(defn load-preset-settings
  "Loads settings corresponding to an article list preset, and navigates to
  articles page."
  [preset-name]
  (when-let [preset (-> @(subscribe [:articles/filter-presets])
                        (get preset-name))]
    (load-settings-and-navigate
     (merge {:sort-by :content-updated
             :sort-dir :desc}
            preset))))

(reg-fx ::load-preset load-preset-settings)

(reg-event-fx :project-articles/load-preset [trim-v]
              (fn [_ [preset-name]]
                {::load-preset preset-name}))

(reg-event-fx :project-articles/reload-list [trim-v]
              (fn [{:keys [db]} []]
                (al/reload-list (get-context-from-db db) :transition)
                {}))

(defn- Panel [child]
  (when @(subscribe [:active-project-id])
    [:div.project-content
     [ArticleListPanel (get-context) {:display-mode :data}]
     child]))

(def-panel :project? true :panel panel
  :uri "/data" :params [project-id] :name articles-data
  :on-route (let [panel [:project :project :articles-data]
                  context (get-context)
                  active-panel @(subscribe [:active-panel])
                  panel-changed? (not= panel active-panel)
                  data-item @(subscribe [::al/articles-query context])
                  set-panel [:set-active-panel panel]
                  have-project? @(subscribe [:have? [:project project-id]])
                  load-params [:article-list/load-url-params context]
                  sync-params #(al/sync-url-params context)
                  set-transition [::al/set-recent-nav-action context :transition]]
              (cond (not have-project?)
                    (do (dispatch [:require [:project project-id]])
                        (dispatch [:data/after-load [:project project-id]
                                   :project-articles-project
                                   (list load-params set-panel)]))
                    panel-changed?
                    (do (dispatch [:data/after-load data-item
                                   :project-articles-route
                                   (list set-panel #(js/setTimeout sync-params 30))])
                        (dispatch set-transition)
                        (al/require-list context)
                        (al/reload-list context))
                    :else
                    (dispatch load-params)))
  :content (fn [child] [Panel child]))
