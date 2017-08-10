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
    (let [article-id @(subscribe [:review/task-id])]
      [:div
       (with-loader [[:review/task]] {}
         [article-info-view article-id :show-labels? false])
       (when article-id
         [:div {:style {:margin-top "1em"}}
          [label-editor-view article-id]])
       child])))
