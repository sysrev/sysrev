(ns sysrev.views.panels.project.analytics.common
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.state.nav :refer [project-uri]]))

(defn beta-message []
  (let [project-id @(subscribe [:project/active-url-id])]
    [:span "Analytics is in Beta - we need your"
     [:a {:href (project-uri project-id "/analytics/feedback")} " feedback"] "."]))
