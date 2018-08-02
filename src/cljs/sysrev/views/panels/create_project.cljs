(ns sysrev.views.panels.create-project
  (:require
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer [dispatch]]
   [sysrev.views.base :refer [panel-content]]))

(defn CreateProjectForm
  "Form for creating a project"
  [state]
  (let [project-name (r/cursor state [:project-name])
        create-project (fn [event]
                         (.preventDefault event)
                         (dispatch [:action [:create-project @project-name]]))]
    [:form {:on-submit create-project}
     [:div.ui.action.input.fluid
      [:input {:type "text"
               :placeholder "Project Name"
               :on-change (fn [event]
                            (reset! project-name
                                    (-> event
                                        (aget "target")
                                        (aget "value")))
                            (.preventDefault event))}]
      [:button.ui.button.primary "Create"]]]))

(defn CreateProject [state]
  [:div.panel
   [:div.ui.secondary.segment
    [:h4.ui.dividing.header
     "Create a New Project"]
    [CreateProjectForm state]]])
