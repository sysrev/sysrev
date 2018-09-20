(ns sysrev.views.panels.project.articles
  (:require [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx trim-v reg-fx]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [use-new-article-list?]]
            [sysrev.state.nav :refer [project-uri active-project-id]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.core :refer [ArticleListPanel]]
            [sysrev.views.article-list.filters :as afilter]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :project :articles])

(defn- get-context-from-db [db]
  (let [project-id (active-project-id db)]
    {:panel panel
     :base-uri (project-uri project-id "/articles")
     :article-base-uri (project-uri project-id "/article")
     :defaults {:filters []}}))

(reg-sub
 ::article-list-context
 :<- [:project/uri]
 :<- [:project/overall-label-id]
 (fn [[project-uri overall-id]]
   {:panel panel
    :base-uri (str project-uri "/articles")
    ;; TODO: make /article/:id route
    :article-base-uri (str project-uri "/article")
    :defaults {:filters [#_ {:label-id overall-id}]}}))

(defn get-context []
  @(subscribe [::article-list-context]))

(reg-sub
 :project-articles/article-id
 :<- [:active-panel]
 :<- [:article-list/article-id {:panel panel}]
 (fn [[active-panel article-id]]
   (when (= active-panel panel)
     article-id)))

(reg-sub-raw
 :project-articles/editing?
 (fn [_]
   (reaction
    (let [context (get-context)
          article-id @(subscribe [:article-list/article-id context])
          active-panel @(subscribe [:active-panel])]
      (when (= active-panel panel)
        @(subscribe [:article-list/editing? context article-id]))))))

(reg-sub-raw
 :project-articles/resolving?
 (fn [_]
   (reaction
    (let [context (get-context)
          article-id @(subscribe [:article-list/article-id context])
          active-panel @(subscribe [:active-panel])]
      (when (= active-panel panel)
        @(subscribe [:article-list/resolving? context article-id]))))))

(defn nav-action []
  [::al/get (get-context) [:recent-nav-action]])

(defn reset-nav-action []
  [::al/set-recent-nav-action (get-context) nil])

(defn show-article [article-id]
  [:article-list/set-active-article (get-context) article-id])

(defn hide-article []
  [:article-list/set-active-article (get-context) nil])

(reg-event-fx
 :project-articles/hide-article [trim-v]
 (fn [{:keys [db]} []]
   {:dispatch [:article-list/set-active-article
               (get-context-from-db db) nil]}))

(defn reset-filters []
  (dispatch [::al/reset-filters (get-context)]))

(defn- load-settings-and-navigate
  "Loads article list settings and navigates to the page from another panel,
  while maintaining clean browser navigation history for Back/Forward."
  [{:keys [filters display sort-by sort-dir] :as settings}]
  (let [context (get-context)]
    (dispatch [:article-list/load-settings
               (get-context) settings])
    (dispatch [::al/navigate context :redirect? false])
    (util/scroll-top)))

(defn load-consensus-settings
  "Loads settings corresponding to a consensus category from graphs on overview
  page, and navigates to articles page."
  [& {:keys [status inclusion]}]
  (let [display {:show-inclusion true}
        filters [{:consensus {:status status
                              :inclusion inclusion}}]]
    (load-settings-and-navigate
     {:filters filters
      :display display
      :sort-by :content-updated
      :sort-dir :desc})))

(defn load-member-label-settings
  "Loads settings corresponding to a user's article count from graphs on
  overview page, and navigates to articles page."
  [user-id]
  (let [display {:show-inclusion true
                 :show-labels false
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
  (when-let [preset (get (afilter/filter-presets) preset-name)]
    (load-settings-and-navigate
     (merge {:sort-by :content-updated
             :sort-dir :desc}
            preset))))

(reg-fx ::load-preset load-preset-settings)

(reg-event-fx
 :project-articles/load-preset [trim-v]
 (fn [_ [preset-name]]
   {::load-preset preset-name}))

(reg-event-fx
 :project-articles/reload-list [trim-v]
 (fn [{:keys [db]} []]
   (al/reload-list (get-context-from-db db) :transition)
   {}))

(defn set-group-status []
  ;; TODO: function
  nil)

(defn set-inclusion-status []
  ;; TODO: function
  nil)

(when use-new-article-list?
  (defmethod panel-content [:project :project :articles] []
    (fn [child]
      (when-let [project-id @(subscribe [:active-project-id])]
        [:div.project-content
         [ArticleListPanel (get-context)]
         child]))))
