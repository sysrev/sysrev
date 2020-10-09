(ns sysrev.views.panels.project.review
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.core :refer [reload require-data]]
            [sysrev.views.article :refer [ArticleInfo]]
            [sysrev.views.group-label :refer [GroupLabelEditor]]
            [sysrev.views.review :refer [LabelAnswerEditor]]
            [sysrev.macros :refer-macros [with-loader def-panel]]))

(def panel [:project :review])

(defn- Panel [child]
  (let [project-id @(subscribe [:active-project-id])
        article-id @(subscribe [:review/task-id])]
    (if (= article-id :none)
      [:div.project-content>div.ui.segment
       [:h4.header.no-review-articles "No articles found needing review"]]
      [:div.project-content
       (with-loader [[:review/task project-id]] {}
         [:div
          [GroupLabelEditor @(subscribe [:visible-article-id])]
          [ArticleInfo article-id :show-labels? false :context :review]])
       (when article-id [LabelAnswerEditor article-id])
       child])))

(def-panel :project? true :panel panel
  :uri "/review" :params [project-id] :name review
  :on-route (let [have-project? (and project-id @(subscribe [:have? [:project project-id]]))
                  set-panel [:set-active-panel panel]
                  set-panel-after #(dispatch [:data/after-load % :review-route set-panel])]
              (when-not have-project? (dispatch set-panel))
              (let [task-id @(subscribe [:review/task-id])]
                (if (integer? task-id)
                  (do (set-panel-after [:article project-id task-id])
                      (reload :article project-id task-id))
                  (set-panel-after [:review/task project-id]))
                (when (= task-id :none)
                  (reload :review/task project-id))
                (require-data :review/task project-id)))
  :content (fn [child] [Panel child]))
