(ns sysrev.views.create-project
  (:require [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.util :as u]))

(def view :create-project)

(defn- CreateProjectForm []
  (let [project-name (subscribe [:view-field view [:project-name]])
        create-project #(dispatch [:action [:create-project @project-name]])]
    [:form {:on-submit (u/wrap-prevent-default create-project)}
     [:div.ui.action.input.fluid
      [:input {:type "text"
               :placeholder "Project Name"
               :on-change (u/wrap-prevent-default
                           #(dispatch-sync [:set-view-field view [:project-name]
                                            (-> % .-target .-value)])) }]
      [:button.ui.button.primary "Create"]]]))

(defn CreateProject []
  [:div.ui.secondary.segment
   [:h4.ui.dividing.header
    "Create a New Project"]
   [CreateProjectForm]])
