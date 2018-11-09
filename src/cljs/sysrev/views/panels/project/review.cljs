(ns sysrev.views.panels.project.review
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]
   [sysrev.views.article :refer [ArticleInfo]]
   [sysrev.views.review :refer [LabelEditor]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmethod panel-content [:project :review] []
  (fn [child]
    (let [project-id @(subscribe [:active-project-id])
          article-id @(subscribe [:review/task-id])]
      (if (= article-id :none)
        [:div.project-content
         [:div.ui.segment
          [:h4.header.no-review-articles "No articles found needing review"]]]
        [:div.project-content
         (with-loader [[:review/task project-id]] {}
           [ArticleInfo article-id
            :show-labels? false
            :context :review])
         (when article-id
           [LabelEditor article-id])
         child]))))
