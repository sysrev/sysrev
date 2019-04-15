(ns sysrev.views.panels.org.projects
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.util :refer [vector->hash-map]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.views.project-list :refer [ProjectsListSegment]]
            [sysrev.views.semantic :refer [Message MessageHeader]]))

(def ^:private panel [:org :projects])

(def state (r/cursor app-db [:state :panels panel]))

(defn get-org-projects! []
  (let [retrieving? (r/cursor state [:retrieving-projects?])
        error (r/cursor state [:retrieving-projects-error])
        projects (r/cursor state [:group-projects])]
    (reset! retrieving? true)
    (GET (str "/api/org/" @(subscribe [:current-org]) "/projects")
         {:header {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (let [user-projects (vector->hash-map @(subscribe [:self/projects]) :project-id)
                           group-projects (-> (get-in response [:result :projects]))]
                       (reset! retrieving? false)
                       (reset! projects (->> group-projects
                                             (map #(assoc % :member? (get-in user-projects [(:project-id %) :member?])))))))
          :error-handler (fn [error-response]
                           (reset! retrieving? false)
                           (reset! error (get-in error-response
                                                 [:response :error :message])))})))
(defn OrgProjects
  []
  (let [error (r/cursor state [:retrieving-projects-error])
        group-projects (r/cursor state [:group-projects])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (get-org-projects!)
        [:div
         (when (some #{"admin" "owner"} @(subscribe [:current-org-permissions]))
           [CreateProject @(subscribe [:current-org])])
         [ProjectsListSegment "Group Projects" @group-projects false]
         (when-not (empty? @error)
           [Message {:negative true
                     :onDismiss #(reset! error "")}
            [MessageHeader {:as "h4"} "Get Group Projects error"]
            @error])])})))
