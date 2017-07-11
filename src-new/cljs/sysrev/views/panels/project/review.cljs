(ns sysrev.views.panels.project.review
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmethod panel-content [:project :review] []
  (fn [child]
    [:div.ui.segment
     (with-loader [[:review/article-id]] {:dimmer true}
       (let [article-id @(subscribe [:review/article-id])]
         [:div
          [:p (str "Got article ID #" article-id)]
          child]))]))
