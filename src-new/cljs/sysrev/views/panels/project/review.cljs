(ns sysrev.views.panels.project.review
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components]
   [sysrev.views.article :refer [article-info-view]]
   [sysrev.views.review])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmethod panel-content [:project :review] []
  (fn [child]
    (with-loader [[:review/task]] {}
      (let [article-id @(subscribe [:review/task-id])]
        [:div
         [article-info-view article-id]
         child]))))
