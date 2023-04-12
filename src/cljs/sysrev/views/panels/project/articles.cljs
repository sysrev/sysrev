(ns sysrev.views.panels.project.articles
  (:require [re-frame.core :refer
             [dispatch reg-event-fx reg-fx reg-sub
              reg-sub-raw subscribe trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.state.nav :refer [active-project-id project-uri]]
            [sysrev.util :as util]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.core :as al-c :refer [ArticleListPanel]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :articles])

(defn- get-context-from-db [db]
  (let [project-id (active-project-id db)]
    {:panel panel
     :base-uri (project-uri project-id "/articles")
     :article-base-uri (project-uri project-id "/article")
     :defaults {:filters []}}))

(reg-sub ::article-list-context
         :<- [:project/uri]
         (fn [project-uri]
           {:panel panel
            :base-uri (str project-uri "/articles")
            :article-base-uri (str project-uri "/article")
            :defaults {:filters []}}))

(defn get-context []
  @(subscribe [::article-list-context]))

(reg-sub :project-articles/article-id
         :<- [:active-panel]
         :<- [:article-list/article-id {:panel panel}]
         (fn [[active-panel article-id]]
           (when (= active-panel panel)
             article-id)))

(reg-sub-raw :project-articles/editing?
             (fn [_]
               (reaction
                (let [context (get-context)
                      article-id @(subscribe [:article-list/article-id context])
                      active-panel @(subscribe [:active-panel])]
                  (when (= active-panel panel)
                    @(subscribe [:article-list/editing? context article-id]))))))

(reg-sub-raw :project-articles/resolving?
             (fn [_]
               (reaction
                (let [context (get-context)
                      article-id @(subscribe [:article-list/article-id context])
                      active-panel @(subscribe [:active-panel])]
                  (when (= active-panel panel)
                    @(subscribe [:article-list/resolving? context article-id]))))))

(reg-event-fx :project-articles/hide-article [trim-v]
              (fn [{:keys [db]}]
                {:dispatch [:article-list/set-active-article
                            (get-context-from-db db) nil]}))

(reg-event-fx :articles/load-source-filters [trim-v]
              (fn [{:keys [db]} [source-ids]]
                (let [context (get-context-from-db db)]
                  {:article-list/load-source-filters [context source-ids]})))

(reg-event-fx :articles/load-export-settings [trim-v]
              (fn [{:keys [db]} [export-type navigate]]
                (let [context (get-context-from-db db)]
                  {:article-list/load-export-settings [context export-type navigate]})))

(defn load-settings-and-navigate
  "Loads article list settings and navigates to the page from another panel,
  while maintaining clean browser navigation history for Back/Forward."
  [{:keys [filters display sort-by sort-dir] :as settings}]
  (al-c/load-settings-and-navigate (get-context) settings))

(defn load-member-label-settings
  "Loads settings corresponding to a user's article count from graphs on
  overview page, and navigates to articles page."
  [user-id]
  (let [display {:show-inclusion true
                 :show-labels false
                 :show-notes false}
        filters [{:consensus {:status nil
                              :inclusion nil}}
                 {:has-label {:users [user-id]
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
                 :show-labels false
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

(defn load-consensus-settings
  "Loads settings corresponding to a consensus category from graphs on overview
  page, and navigates to articles page."
  [& {:keys [status inclusion]}]
  (load-settings-and-navigate
   {:filters [{:consensus {:status status, :inclusion inclusion}}]
    :display {:show-inclusion true}
    :sort-by :content-updated
    :sort-dir :desc}))

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
     [ArticleListPanel (get-context) {:display-mode :list}]
     child]))

(def-panel :project? true :panel panel
  :uri "/articles" :params [project-id] :name articles
  :on-route (let [panel [:project :project :articles]
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
                                   (list set-panel
                                         [:scroll-top]
                                         #(js/setTimeout sync-params 30))])
                        (dispatch set-transition)
                        (al/require-list context)
                        (al/reload-list context))
                    :else
                    (dispatch load-params)))
  :content (fn [child] [Panel child]))
