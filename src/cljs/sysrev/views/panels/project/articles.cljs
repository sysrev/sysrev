(ns sysrev.views.panels.project.articles
  (:require [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.article-list :as al]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.util]
            [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :project :articles])

(defmethod al/panel-defaults panel []
  {:panel panel})

(def initial-state {:article-list {}})
(defonce state (r/cursor app-db [:state :panels panel]))
(defonce al-state (r/cursor app-db [:state :panels panel :article-list]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn current-state []
  (al/current-state @al-state (al/panel-defaults panel)))

(reg-sub
 :project-articles/article-id
 :<- [:active-panel]
 :<- [:article-list/article-id panel]
 (fn [[active-panel article-id]]
   (when (= active-panel panel)
     article-id)))

(reg-sub
 :project-articles/editing?
 :<- [:active-panel]
 :<- [:article-list/editing? panel]
 (fn [[active-panel editing?]]
   (when (= active-panel panel)
     editing?)))

(reg-sub
 :project-articles/resolving?
 :<- [:active-panel]
 :<- [:article-list/resolving? panel]
 (fn [[active-panel resolving?]]
   (when (= active-panel panel)
     resolving?)))

(defn resolving? []
  ;; TODO: function
  false)

(defn show-article [article-id]
  [:article-list/set-active-article panel article-id])

(def hide-article
  [:article-list/set-active-article panel nil])

(defn reset-filters []
  (al/reset-filters al-state (current-state)))

(defn set-group-status []
  ;; TODO: function
  nil)

(defn set-inclusion-status []
  ;; TODO: function
  nil)

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      [:div.project-content
       [al/ArticleListPanel al-state (al/panel-defaults panel)]
       child])))
