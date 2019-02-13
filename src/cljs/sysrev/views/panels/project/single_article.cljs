(ns sysrev.views.panels.project.single-article
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-sub-raw
                                   reg-event-db reg-event-fx]]
            [reagent.ratom :refer [reaction]]
            [sysrev.state.nav :refer [project-uri active-project-id]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.article-list.base :as alist-b]
            [sysrev.views.article-list.core :as alist-c]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def panel [:project :project :single-article])

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
    :article-base-uri (str project-uri "/article")
    :defaults {:filters []}}))

(defn get-context [] @(subscribe [::article-list-context]))

(reg-sub
 :article-view/article-id
 :<- [:active-panel]
 :<- [:article-list/article-id {:panel panel}]
 (fn [[active-panel article-id]]
   (when (= active-panel panel)
     article-id)))

(reg-event-fx
 :article-view/set-active-article
 (fn [{:keys [db]} [_ article-id]]
   (let [context (get-context-from-db db)]
     {:dispatch [:article-list/set-active-article context article-id]})))

(reg-sub-raw
 :article-view/editing?
 (fn [_]
   (reaction
    (let [context (get-context)
          article-id @(subscribe [:article-list/article-id context])
          active-panel @(subscribe [:active-panel])]
      (when (= active-panel panel)
        @(subscribe [:article-list/editing? context article-id]))))))

(reg-sub-raw
 :article-view/resolving?
 (fn [_]
   (reaction
    (let [context (get-context)
          article-id @(subscribe [:article-list/article-id context])
          active-panel @(subscribe [:active-panel])]
      (when (= active-panel panel)
        @(subscribe [:article-list/resolving? context article-id]))))))

(defn ArticlePanel []
  (let [context @(subscribe [::article-list-context])
        article-id @(subscribe [:article-view/article-id])
        project-id @(subscribe [:active-project-id])]
    (when (and project-id (integer? article-id))
      (with-loader [[:article project-id article-id]] {}
        [:div
         [:a.ui.fluid.left.labeled.icon.button
          {:href (:base-uri context)
           :style {:margin-bottom "1em"}}
          [:i.left.arrow.icon]
          "Back to Article List"]
         [alist-c/ArticleContent context article-id]]))))

(defmethod panel-content panel []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      [:div.project-content
       [ArticlePanel]
       child])))
