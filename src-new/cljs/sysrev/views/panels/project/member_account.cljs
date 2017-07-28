(ns sysrev.views.panels.project.member-account
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmethod panel-content [:project :member-account] []
  (fn [child]
    (when-let [user-id @(subscribe [:self/user-id])]
      (with-loader [[:project]
                    [:member/articles user-id]] {}
        (let [articles @(subscribe [:member/articles user-id])]
          [:div
           [:h3 (str "Found " (count articles) " labeled articles")]])))))
