(ns sysrev.views.panels.project.analytics.concordance
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [sysrev.views.charts :as charts]
            [sysrev.views.components.core :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :concordance])

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})


(defmethod panel-content [:project :project :analytics :concordance] []
  (fn [child]
    [:div.project-content
     [:div.ui.aligned.segment
      [:h4 "User Concordance"]
      [:div.ui.divider]
      [:span "a chart goes here"]
      ]
     ;[:div.ui.center.aligned.segment
     ; [charts/pie-chart
     ;  [["dogs" 100 (:green colors)]]]]
     child]))
