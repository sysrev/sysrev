(ns sysrev.views.panels.select-project
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [reagent.core :as r]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.panels.create-project :refer [CreateProject]]
   [sysrev.util :refer [go-back]]))

(def panel [:select-project])

(def initial-state {:create-project nil})
(def state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn ProjectListItem [{:keys [project-id name member?]}]
  [:div.item
   {:style {:width "100%"}}
   [:div.ui.fluid.labeled.button
    {:on-click (if member?
                 #(dispatch [:project/navigate project-id])
                 #(dispatch [:action [:join-project project-id]]))}
    (if member?
      [:div.ui.button "Open"]
      [:div.ui.blue.button "Join"])
    [:div.ui.fluid.basic.label
     {:style {:text-align "left"}}
     name]]])

(defn ProjectsListSegment [title projects]
  [:div.projects-list
   [:div.ui.top.attached.header.segment
    [:h4 title]]
   [:div.ui.bottom.attached.segment
    [:div.ui.middle.aligned.relaxed.list
     (doall
      (->> projects
           (map (fn [{:keys [project-id] :as project}]
                  ^{:key project-id}
                  [ProjectListItem project]))))]]])

(defn SelectProject []
  (ensure-state)
  (let [all-projects @(subscribe [:self/projects true])
        member-projects (->> all-projects (filter :member?))
        available-projects (->> all-projects (remove :member?))]
    [:div
     [CreateProject state]
     [ProjectsListSegment "Your Projects" member-projects]
     [ProjectsListSegment "Available Projects" available-projects]]))

(defmethod panel-content panel []
  (fn [child]
    [SelectProject]))
