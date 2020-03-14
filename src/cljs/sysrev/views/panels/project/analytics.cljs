(ns sysrev.views.panels.project.analytics
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics])

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})

(defmethod panel-content [:project :project :analytics] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      (if (false? @(subscribe [:project/has-articles?]))
        (do (nav/nav-redirect (project-uri project-id "/add-articles")
                              :scroll-top? true)
            [:div])
        [:div.project-content
         [:div.ui.center.aligned.segment
          [:iframe
           {:src "https://docs.google.com/forms/d/e/1FAIpQLSejOldFq7U0zo-8AwxRwqrV77CqD3x4uIFOiwcDd-WsNIuvhQ/viewform?embedded=true"
            :width "640" :height "700px" :frameborder "0" :marginheight "0" :marginwidth "0"
            :overflow "visible"} "Loading…"]]
         child]))))
