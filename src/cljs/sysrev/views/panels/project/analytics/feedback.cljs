(ns sysrev.views.panels.project.analytics.feedback
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.data.core :refer [reload]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :feedback])

(defn- Panel []
  [:div.ui.segment
   [:h2 "Analytics Feedback"]
   [:span "Thanks for trying out the new analytics features.  We plan to realease improvements and more analytics in the future including:"]
   [:ul
    [:li [:b "Concordance"] "- Add evaluation for categorical labels."]
    [:li [:b "Time"] "- See how quickly your project is getting completed. Estimate how much time is left. Watch reviewer completion rates."]
    [:li [:b "Similarity"] "- A visualization of document-document similarity.  Eventually with the option to review and remove duplicates"]
    [:li [:b "Compensation"] "- Track and plan project spending."]]
   [:span "Let us know if you find a problem in the existing workflows, or if you have a new suggestion in the below survey."]
   [:div {:style {:text-align "center"}}
    [:iframe {:src (str "https://docs.google.com/forms/d/e"
                        "/1FAIpQLSebmFD_5X-Dzj8SmEwT_t6T5UkNlM5Cj2n5aLseIl3bNpdO6A"
                        "/viewform?embedded=true")
              :width "800" :height "1200" :frame-border "0"
              :margin-height "0" :margin-width "0"}
     "Loading…"]]])

(def-panel :project? true :panel panel
  :uri "/analytics/feedback" :params [project-id] :name analytics-feedback
  :on-route (do (reload :project project-id)
                (dispatch [:set-active-panel panel]))
  :content [Panel])
