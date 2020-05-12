(ns sysrev.views.panels.project.analytics.labels
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [sysrev.views.charts :as charts]
            [sysrev.views.semantic :refer [Segment Grid Row Column]]
            [sysrev.views.components.core :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :labels])

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})


(defmethod panel-content [:project :project :analytics :labels] []
  (fn [child]
    [:div.ui.aligned.segment
     [Grid {:stackable true}
      [Row
       [Column {:width 12}
        [:h3 "Label Analysis - Coming Soon" ]
        [:span "Beta version of analytics. Email errors and suggestions to us info@insilica.co."]
        [:br]
        [:span "Label analysis will provide interactive tools to get counts of label answers under different conditions."]
        [:br]
        [:span "Give us your feedback below "
         [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics"]]]]
      [:div.ui.divider]
      [Row
       [Column {:width 16}
        [:div {:style {:text-align "center"}}
        [:iframe {:src "https://docs.google.com/forms/d/e/1FAIpQLSebmFD_5X-Dzj8SmEwT_t6T5UkNlM5Cj2n5aLseIl3bNpdO6A/viewform?embedded=true"
                  :width "640" :height "547" :frameborder "0" :marginheight "0" :marginwidth "0"} "Loading…"]]]]]
     child]))
