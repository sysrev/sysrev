(ns sysrev.views.panels.project.review
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.article :refer [ArticleInfo]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.review :refer [LabelAnswerEditor GroupLabelPreview]]
            [sysrev.macros :refer-macros [with-loader]]))

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
           [:div
            [GroupLabelPreview @(subscribe [:visible-article-id])]
            [ArticleInfo article-id :show-labels? false :context :review]])
         (when article-id [LabelAnswerEditor article-id])
         child]))))
