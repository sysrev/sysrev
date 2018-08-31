(ns sysrev.views.panels.project.articles
  (:require [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [use-new-article-list?]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.core :refer [ArticleListPanel]]
            [sysrev.util]
            [sysrev.shared.util :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :project :articles])

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

(defn nav-action []
  [::al/get (get-context) [:recent-nav-action]])

(defn reset-nav-action []
  [::al/set-recent-nav-action (get-context) nil])

(defn show-article [article-id]
  [:article-list/set-active-article (get-context) article-id])

(defn hide-article []
  [:article-list/set-active-article (get-context) nil])

(defn reset-filters []
  (dispatch [::al/reset-filters (get-context)]))

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
