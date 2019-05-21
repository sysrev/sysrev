(ns sysrev.views.create-project
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :as util]
            [sysrev.views.semantic :refer [Form FormField Input Message MessageHeader Segment Header Button Dropdown]])
  (:require-macros [reagent.interop :refer [$]]))

(def view :create-project)

(def-action :create-project
  :uri (fn [_] "/api/create-project")
  :content (fn [project-name] {:project-name project-name})
  :process (fn [_ _ {:keys [success message project] :as result}]
             (if success
               {:dispatch-n
                (list [:fetch [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

(def-action :create-org-project
  :uri (fn [project-nae org-id] (str "/api/org/" org-id "/project"))
  :content (fn [project-name org-id] {:project-name project-name})
  :process (fn [_ _ {:keys [success message project] :as result}]
             (if success
               {:dispatch-n
                (list [:fetch [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

(defn- CreateProjectForm [& [initial-org-id]]
  (let [project-name (subscribe [:view-field view [:project-name]])
        orgs (subscribe [:self/orgs])
        current-org-id (r/atom (or initial-org-id
                                   "current-user"))
        create-project #(if (= @current-org-id "current-user")
                          (dispatch [:action [:create-project @project-name]])
                          (dispatch [:action [:create-org-project @project-name @current-org-id]]))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Form {:class "create-project"
               :on-submit (util/wrap-prevent-default create-project)}
         [Input {:placeholder "Project Name"
                 :class "project-name"
                 :fluid true
                 :action (r/as-element [Button {:primary true
                                                :class "create-project"} "Create"])
                 :on-change (util/wrap-prevent-default
                             #(dispatch-sync [:set-view-field view [:project-name]
                                              (-> % .-target .-value)]))}]
         (when (and (not (empty? (->> @orgs
                                      (filter #(some #{"owner" "admin"} (:permissions %))))))
                    (nil? initial-org-id))
           [:div {:style {:margin-top "0.5em"}} "Owner "
            [Dropdown {:options (-> (map #(hash-map :text (:group-name %)
                                                    :value (:id %)) @orgs)
                                    (conj {:text (-> @(subscribe [:self/identity])
                                                     :email
                                                     (clojure.string/split #"@")
                                                     first)
                                           :value "current-user"}))
                       :value @current-org-id
                       :on-change (fn [event data]
                                    (reset! current-org-id
                                            ($ data :value)))}]])])
      :component-did-mount (fn [this] (dispatch [:read-orgs!]))})))

(defn CreateProject [& [initial-org-id]]
  [Segment {:secondary true}
   [Header {:as "h4"
            :dividing true}
    "Create a New Project"]
   [CreateProjectForm initial-org-id]])
