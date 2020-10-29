(ns sysrev.views.panels.project.analytics.common
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.state.nav :refer [project-uri]]))

(defn BetaMessage []
  (let [project-id @(subscribe [:project/active-url-id])
        url (project-uri project-id "/analytics/feedback")]
    [:div.ui.message
     [:div.content "Analytics is in Beta - we need your "
      [:a {:href url} "feedback"] "."]]))
