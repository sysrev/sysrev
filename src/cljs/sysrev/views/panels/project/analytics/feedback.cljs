(ns sysrev.views.panels.project.analytics.feedback
  (:require
    [reagent.core :as r]
    [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
    [sysrev.views.panels.project.description :refer [ProjectDescription]]
    [sysrev.nav :as nav]
    [sysrev.state.nav :refer [project-uri]]
    [sysrev.views.base :refer [panel-content]]
    [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
    [sysrev.shared.charts :refer [processed-label-color-map]]
    [sysrev.views.charts :as charts]
    [sysrev.views.components.core :refer
     [primary-tabbed-menu secondary-tabbed-menu]]
    [sysrev.views.semantic :refer [Segment Grid Row Column Checkbox Dropdown Select Button Modal]]
    [sysrev.macros :refer-macros [with-loader setup-panel-state]]
    [sysrev.charts.chartjs :as chartjs]
    [sysrev.data.core :refer [def-data]]
    [sysrev.views.components.core :refer [selection-dropdown]]
    [sysrev.util :as util]
    [goog.string :as gstring]))

(declare panel)

(setup-panel-state panel [:project :project :analytics :feedback])

(defmethod panel-content [:project :project :analytics :feedback] []
           (fn [child]
               [:div.ui.aligned.segment
                [:h2 "Analytics Feedback"]
                [:span "Thanks for trying out the new analytics features.  We plan to realease improvements and more analytics in the future including:"]
                [:ul
                 [:li [:b"Concordance"] "- Add evaluation for categorical labels."]
                 [:li [:b"Time"] "- See how quickly your project is getting completed. Estimate how much time is left. Watch reviewer completion rates."]
                 [:li [:b"Similarity"] "- A visualization of document-document similarity.  Eventually with the option to review and remove duplicates"]
                 [:li [:b"Compensation"] "- Track and plan project spending."]]
                [:span "Let us know if you find a problem in the existing workflows, or if you have a new suggestion in the below survey."]
                [:div {:style {:text-align "center"}}
                 [:iframe {
                           :src "https://docs.google.com/forms/d/e/1FAIpQLSebmFD_5X-Dzj8SmEwT_t6T5UkNlM5Cj2n5aLseIl3bNpdO6A/viewform?embedded=true"
                           :width "640" :height "1100" :frameborder "0" :marginheight "0" :marginwidth "0"} "Loading…"]]]))