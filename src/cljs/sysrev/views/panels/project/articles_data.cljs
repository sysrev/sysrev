(ns sysrev.views.panels.project.articles-data
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.core :refer [ArticleListPanel]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :articles-data])

(reg-sub ::article-list-context
         :<- [:project/uri]
         (fn [project-uri]
           {:panel panel
            :base-uri (str project-uri "/data")
            :article-base-uri (str project-uri "/article")
            :defaults {:filters []
                       :display {:show-labels true}}}))

(defn get-context []
  @(subscribe [::article-list-context]))

(defn- Panel [child]
  (when @(subscribe [:active-project-id])
    [:div.project-content
     [ArticleListPanel (get-context) {:display-mode :data}]
     child]))

(def-panel :project? true :panel panel
  :uri "/data" :params [project-id] :name articles-data
  :on-route (let [context (get-context)
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
