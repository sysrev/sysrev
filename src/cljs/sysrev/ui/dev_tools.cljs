(ns sysrev.ui.dev-tools
  (:require [sysrev.state.core :as st]
            [sysrev.ajax :as ajax]))

(defn site-dev-tools-component []
  (when-let [user-id (st/current-user-id)]
    (when (st/admin-user? user-id)
      [:div.ui.container.segments
       [:div.ui.top.attached.header.segment
        [:h4 "Dev tools"]]
       [:div.ui.bottom.attached.segment.center.aligned
        [:div.ui.buttons
         [:a.ui.small.primary.button
          {:on-click #(ajax/post-clear-query-cache)}
          "Clear query cache"]]]])))
