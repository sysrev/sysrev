(ns sysrev.views.create-project
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :as util]
            [sysrev.views.semantic :refer
             [Form Input Segment Header Button Dropdown]]))

(def view :create-project)
(defn field [path] (subscribe [:view-field view path]))
(defn set-field-now [path val] (dispatch-sync [:set-view-field view path val]))

(def-action :create-project
  :uri (fn [_] "/api/create-project")
  :content (fn [project-name] {:project-name project-name})
  :process (fn [_ _ {:keys [success message project]}]
             (if success
               {:dispatch-n
                (list [:reload [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

(def-action :create-org-project
  :uri (fn [_ org-id] (str "/api/org/" org-id "/project"))
  :content (fn [project-name _] {:project-name project-name})
  :process (fn [_ _ {:keys [success message project]}]
             (if success
               {:dispatch-n
                (list [:reload [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

(defn- CreateProjectForm [& [initial-org-id]]
  (let [project-name (field [:project-name])
        orgs (subscribe [:self/orgs])
        current-org-id (r/atom (or initial-org-id "current-user"))
        create-project #(if (= @current-org-id "current-user")
                          (dispatch [:action [:create-project @project-name]])
                          (dispatch [:action [:create-org-project @project-name @current-org-id]]))]
    (r/create-class
     {:reagent-render
      (fn [_]
        [Form {:class "create-project"
               :on-submit (util/wrap-prevent-default create-project)}
         [Input {:placeholder "Project Name"
                 :class "project-name"
                 :fluid true
                 :action (r/as-element [Button {:primary true
                                                :class "create-project"} "Create"])
                 :on-change (util/on-event-value #(set-field-now [:project-name] %))}]
         (when (and (seq (->> @orgs (filter #(some #{"owner" "admin"} (:permissions %)))))
                    (nil? initial-org-id))
           [:div {:style {:margin-top "0.5em"}} "Owner "
            [Dropdown {:options (-> (map #(hash-map :text (:group-name %)
                                                    :value (:group-id %)) @orgs)
                                    (conj {:text @(subscribe [:user/display])
                                           :value "current-user"}))
                       :value @current-org-id
                       :on-change (fn [_event data]
                                    (reset! current-org-id (.-value data)))}]])])
      :component-did-mount (fn [_this]
                             (dispatch [:require [:self-orgs]])
                             (dispatch [:reload [:self-orgs]]))})))

(defn CreateProject [& [initial-org-id]]
  [Segment {:secondary true :class "create-project"}
   [Header {:as "h4" :dividing true} "Create a New Project"]
   [CreateProjectForm initial-org-id]])
