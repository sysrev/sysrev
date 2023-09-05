(ns sysrev.views.panels.project.single-article
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.nav :as nav]
            [sysrev.views.article :refer [ArticleInfo ArticlePredictions]]
            [sysrev.views.group-label :refer [GroupLabelEditor]]
            [sysrev.views.review :as review]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [with-loader def-panel]]))

(def panel [:project :project :single-article])

(defn ArticleContent [article-id]
  (let [editing? @(subscribe [:review/editing? article-id])
        resolving? @(subscribe [:review/resolving? article-id])
        blinded? @(subscribe [:self/blinded?])]
    [:div {:style {:width "100%"}}
     [ArticleInfo article-id
      :show-labels? true
      :private-view? blinded?
      :context :article
      :resolving? resolving?]
     (when editing? [review/LabelAnswerEditor article-id])
     [ArticlePredictions article-id]]))

(defn ArticlePanel []
  (let [article-id @(subscribe [:review/article-id])
        project-id @(subscribe [:active-project-id])
        mobile? (util/mobile?)]
    (when (and project-id (integer? article-id))
      (with-loader [[:article project-id article-id]] {}
        [:div
         [:a.ui.fluid.left.labeled.icon.button
          {:href (project-uri project-id "/articles")
           :style {:margin-bottom (if mobile? "0.75em" "1em")}
           :class (css [mobile? "small"])}
          [:i.left.arrow.icon]
          "Back to Article List"]
         [ArticleContent article-id]]))))

(defn- Panel [child]
  (when @(subscribe [:active-project-id])
    [:div.project-content
     (when (review/display-sidebar?)
       [GroupLabelEditor @(subscribe [:review/article-id])])
     [ArticlePanel]
     child]))

(def-panel :project? true :panel panel
  :uri "/article/:article-id" :params [project-id article-id] :name article-id
  :on-route (let [prev-article-id @(subscribe [:review/article-id])
                  article-id (util/parse-integer article-id)
                  item [:article project-id article-id]
                  have-project? @(subscribe [:have? [:project project-id]])
                  set-panel [:set-active-panel panel]
                  set-article [:review/set-article-id article-id panel]]
              (if (integer? article-id)
                (do (if (not have-project?)
                      (do (dispatch set-panel)
                          (dispatch set-article)
                          (dispatch [:scroll-top]))
                      (dispatch [:data/after-load item :article-route
                                 (cond-> (list set-panel set-article)
                                   ;; scroll to top when entering article page
                                   (not= prev-article-id article-id)
                                   (concat (list [:scroll-top]))) ]))
                    (dispatch [:data/load item]))
                (do (util/log-err "invalid article id")
                    (nav/nav "/"))))
  :content (fn [child] [Panel child]))
