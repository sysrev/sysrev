(ns sysrev.views.create-project
  (:require [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.util :as util]))

(def view :create-project)

(defn- CreateProjectForm []
  (let [project-name (subscribe [:view-field view [:project-name]])
        create-project #(dispatch [:action [:create-project @project-name]])]
    [:form.create-project {:on-submit (util/wrap-prevent-default create-project)}
     [:div.ui.action.input.fluid
      [:input.project-name
       {:type "text"
        :placeholder "Project Name"
        :on-change (util/wrap-prevent-default
                    #(dispatch-sync [:set-view-field view [:project-name]
                                     (-> % .-target .-value)]))}]
      [:button.ui.primary.button.create-project "Create"]]]))

(defn CreateProject []
  [:div.ui.secondary.segment.create-project
   [:h4.ui.dividing.header "Create a New Project"]
   [CreateProjectForm]])
