(ns sysrev.views.panels.org.projects
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.views.project-list :refer [ProjectsListSegment]]
            [sysrev.views.semantic :refer [Message MessageHeader]]
            [sysrev.shared.util :refer [->map-with-key]]))

(def ^:private panel [:org :projects])

(def state (r/cursor app-db [:state :panels panel]))

(defn get-org-projects! []
  (let [retrieving? (r/cursor state [:retrieving-projects?])
        error-msg (r/cursor state [:retrieving-projects-error])
        projects (r/cursor state [:group-projects])]
    (reset! retrieving? true)
    (GET (str "/api/org/" @(subscribe [:current-org]) "/projects")
         {:header {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (let [user-projects (->map-with-key :project-id @(subscribe [:self/projects]))
                           member-of? #(get-in user-projects [% :member?])]
                       (reset! retrieving? false)
                       (reset! projects (map #(assoc % :member? (member-of? (:project-id %)))
                                             (:projects result)))))
          :error-handler (fn [{:keys [error]}]
                           (reset! retrieving? false)
                           (reset! error-msg (:message error)))})))

(defn OrgProjects []
  (let [error (r/cursor state [:retrieving-projects-error])
        group-projects (r/cursor state [:group-projects])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (get-org-projects!)
        [:div
         (when (some #{"admin" "owner"} @(subscribe [:current-org-permissions]))
           [CreateProject @(subscribe [:current-org])])
         [ProjectsListSegment "Group Projects" @group-projects false :id "group-projects"]
         (when-not (empty? @error)
           [Message {:negative true
                     :onDismiss #(reset! error "")}
            [MessageHeader {:as "h4"} "Get Group Projects error"]
            @error])])})))
