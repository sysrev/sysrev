(ns sysrev.views.panels.project.review
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]
   [sysrev.views.article :refer [article-info-view]]
   [sysrev.views.review :refer [label-editor-view]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmethod panel-content [:project :review] []
  (fn [child]
    (let [project-id @(subscribe [:active-project-id])
          article-id @(subscribe [:review/task-id])]
      (if (= article-id :none)
        [:div.project-content
         [:div.ui.segment
          [:h4.header "No articles found needing review"]]]
        [:div.project-content
         (with-loader [[:review/task project-id]] {}
           [article-info-view article-id
            :show-labels? false
            :context :review])
         (when article-id
           [label-editor-view article-id])
         child]))))
