(ns sysrev.views.panels.select-project
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.create-project :refer [CreateProject]]
            [sysrev.util :refer [go-back]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def panel [:select-project])

(def initial-state {:create-project nil})
(def state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn ProjectListItem [{:keys [project-id name member?]}]
  (if member?
    [:a.ui.middle.aligned.grid.segment.project-list-project
     {:href (project-uri project-id "")}
     [:div.row>div.sixteen.wide.column
      [:h4.ui.header.blue-text
       [:i.grey.list.alternate.outline.icon]
       [:div.content
        [:span.project-title name]]]]]
    [:div.ui.middle.aligned.grid.segment.project-list-project.non-member
     [:div.row>div.sixteen.wide.column
      [:h4.ui.header
       [:div.content
        [:div.ui.small.blue.button
         {:style {:margin-right "1em"}
          :on-click #(dispatch [:action [:join-project project-id]])}
         "Join"]
        [:span.project-title name]]]]]))

(defn ProjectsListSegment [title projects member?]
  (with-loader [[:identity]] {}
    (when (or (not-empty projects) (true? member?))
      [:div.ui.segments.projects-list
       {:class (if member? "member" "non-member")}
       (when (loading/item-loading? [:identity])
         [:div.ui.active.inverted.dimmer
          [:div.ui.loader]])
       [:div.ui.segment
        [:h5.ui.header title]]
       (doall
        (->> projects
             (map (fn [{:keys [project-id] :as project}]
                    ^{:key [:projects-list title project-id]}
                    [ProjectListItem project]))))])))

(defn SelectProject []
  (ensure-state)
  (when @(subscribe [:self/logged-in?])
    (let [all-projects @(subscribe [:self/projects true])
          member-projects (->> all-projects (filter :member?))
          available-projects (->> all-projects (remove :member?))]
      [:div
       [CreateProject state]
       [ProjectsListSegment "Your Projects" member-projects true]
       [ProjectsListSegment "Available Projects" available-projects false]])))

(defmethod panel-content panel []
  (fn [child]
    [SelectProject]))
