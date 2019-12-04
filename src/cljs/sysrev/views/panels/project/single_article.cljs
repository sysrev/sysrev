(ns sysrev.views.panels.project.single-article
  (:require [re-frame.core :refer [subscribe reg-sub reg-sub-raw reg-event-fx trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.state.nav :refer [project-uri active-project-id]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.article-list.core :as alist]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [css]]
            [sysrev.macros :refer-macros [with-loader]]))

(def panel [:project :project :single-article])

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

(defn get-context [] @(subscribe [::article-list-context]))

(reg-sub :article-view/article-id
         :<- [:active-panel]
         :<- [:article-list/article-id {:panel panel}]
         (fn [[active-panel article-id]]
           (when (= active-panel panel)
             article-id)))

(reg-event-fx :article-view/set-active-article [trim-v]
              (fn [{:keys [db]} [article-id]]
                (let [context (get-context-from-db db)]
                  {:dispatch [:article-list/set-active-article context article-id]})))

(reg-sub-raw :article-view/editing?
             (fn [_]
               (reaction
                (let [context (get-context)
                      article-id @(subscribe [:article-list/article-id context])
                      active-panel @(subscribe [:active-panel])]
                  (when (= active-panel panel)
                    @(subscribe [:article-list/editing? context article-id]))))))

(reg-sub-raw :article-view/resolving?
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
        project-id @(subscribe [:active-project-id])
        mobile? (util/mobile?)]
    (when (and project-id (integer? article-id))
      (with-loader [[:article project-id article-id]] {}
        [:div
         [:a.ui.fluid.left.labeled.icon.button
          {:href (:base-uri context)
           :style {:margin-bottom (if mobile? "0.75em" "1em")}
           :class (css [mobile? "small"])}
          [:i.left.arrow.icon]
          "Back to Article List"]
         [alist/ArticleContent context article-id]]))))

(defmethod panel-content panel []
  (fn [child]
    (when @(subscribe [:active-project-id])
      [:div.project-content
       [ArticlePanel]
       child])))
