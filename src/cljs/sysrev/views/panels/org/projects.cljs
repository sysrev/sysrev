(ns sysrev.views.panels.org.projects
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx reg-sub reg-event-db trim-v dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.views.panels.user.projects :refer [MakePublic]]
            [sysrev.views.project-list :refer [ProjectsListSegment]]
            [sysrev.views.semantic :refer [Message MessageHeader Divider Segment Header]]
            [sysrev.shared.util :refer [->map-with-key]]
            [sysrev.state.nav :refer [project-uri]]))

(def ^:private panel [:org :projects])

(def state (r/cursor app-db [:state :panels panel]))

(reg-sub :org/projects
         (fn [db [event org-id]]
           (get-in db [:org org-id :projects])))

(reg-event-db
 :org/set-projects!
 [trim-v]
 (fn [db [org-id projects]]
   (assoc-in db [:org org-id :projects] projects)))

(defn get-org-projects! [org-id]
  (let [retrieving? (r/cursor state [:retrieving-projects?])
        error-msg (r/cursor state [:retrieving-projects-error])]
    (reset! retrieving? true)
    (GET (str "/api/org/" org-id "/projects")
         {:header {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (let [user-projects (->map-with-key :project-id @(subscribe [:self/projects]))
                           member-of? #(get-in user-projects [% :member?])]
                       (reset! retrieving? false)
                       (dispatch [:org/set-projects! org-id (map #(assoc % :member? (member-of? (:project-id %)))
                                                                 (:projects result))])))
          :error-handler (fn [{:keys [error]}]
                           (reset! retrieving? false)
                           (reset! error-msg (:message error)))})))

(reg-event-fx :org/get-projects!
              [trim-v]
              (fn [event [org-id]]
                (get-org-projects! org-id)
                {}))

(defn OrgProject
  [{:keys [name project-id settings]}]
    [:div {:id (str "project-" project-id)
         :class "org-project-entry"
         :style {:margin-bottom "1em" :font-size "110%"}}
   [:a {:href (project-uri project-id)
        :style {;;:margin-bottom "0.5em"
                :display "inline-block"
                }} name]
     ;; need a function to determine if user is org-admin or not for this
     #_(when @(subscribe [:users/is-path-user-id-self?])
       (when-not (:public-access settings)
         [:div {:style {:margin-bottom "0.5em"}} [MakePublic {:project-id project-id}]]))
     [Divider]])

(defn OrgProjectList
  [projects]
  (let [{:keys [public private]}
        (-> (group-by #(if (get-in % [:settings :public-access]) :public :private) projects)
            ;; because we need to exclude anything that doesn't explicitly have a settings keyword
            ;; non-public project summaries are given, but without identifying profile information
            (update :private #(filter :settings %)))]
    (if (seq projects)
      [:div.projects
       (when (seq public)
         [Segment
          [Header {:as "h4" :dividing true :style {:font-size "120%"}}
           "Public Projects"]
          [:div {:id "public-projects"}
           (doall (for [project public]
                    ^{:key (:project-id project)}
                    [OrgProject project]))]])
       (when (seq private)
         [Segment
          [Header {:as "h4" :dividing true :style {:font-size "120%"}}
           "Private Projects"]
          [:div {:id "private-projects"}
           (doall (for [project ;;(sort sort-activity private)
                        private]
                    ^{:key (:project-id project)}
                    [OrgProject project]))]])]
      [Segment
       [:h3 "This organization doesn't have public projects"]])))

(defn OrgProjects [{:keys [org-id]}]
  (let [error (r/cursor state [:retrieving-projects-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         (when (some #{"admin" "owner"} @(subscribe [:orgs/org-permissions org-id]))
           [CreateProject org-id])
         [OrgProjectList @(subscribe [:org/projects org-id])]
         (when-not (empty? @error)
           [Message {:negative true
                     :onDismiss #(reset! error "")}
            [MessageHeader {:as "h4"} "Get Group Projects error"]
            @error])])
      :component-did-mount (fn [this]
                             (dispatch [:org/get-projects! org-id]))})))
