(ns sysrev.views.project-list
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.util :refer [go-back]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn- ProjectListItem [{:keys [project-id name member?]}]
  (if member?
    [:a.ui.middle.aligned.grid.segment.project-list-project
     {:href (project-uri project-id "")}
     [:div.row>div.sixteen.wide.column
      [:h4.ui.header.blue-text
       [:i.grey.list.alternate.outline.icon]
       [:div.content
        [:span.project-title name]]]]]
    [:div.ui.middle.aligned.stackable.grid.segment.project-list-project.non-member
     [:div.twelve.wide.column
      [:a {:href (project-uri project-id "")}
       [:h4.ui.header.blue-text
        [:i.grey.list.alternate.outline.icon]
        [:div.content
         [:span.project-title name]]]]]
     [:div.four.wide.right.aligned.column
      [:div.ui.tiny.blue.button
       {:on-click #(dispatch [:action [:join-project project-id]])}
       "Join"]]]))

(defn- ProjectsListSegment [title projects member?]
  (with-loader [[:identity]] {}
    (when (or (not-empty projects) (true? member?))
      [:div.ui.segments.projects-list
       {:class (if member? "member" "non-member")
        :id (if member? "your-projects" "available-projects")}
       (when (loading/item-loading? [:identity])
         [:div.ui.active.inverted.dimmer
          [:div.ui.loader]])
       [:div.ui.segment.projects-list-header
        [:h4.ui.header title]]
       (doall
        (->> projects
             (map (fn [{:keys [project-id] :as project}]
                    ^{:key [:projects-list title project-id]}
                    [ProjectListItem project]))))])))

(defn PublicProjectsList []
  [:div.ui.segments.projects-list
   [:div.ui.segment.projects-list-header
    [:h4.ui.header "Featured Projects"]]
   (doall
    (for [project-id [100 269 2026 844 3144]]
      (when-let [project @(subscribe [:public-projects project-id])]
        ^{:key [:public-project project-id]}
        [ProjectListItem
         {:project-id project-id
          :name (:name project)
          :member? true}])))])

(defn UserProjectListFull []
  (with-loader [[:identity] [:public-projects]] {}
    (when @(subscribe [:self/logged-in?])
      (let [all-projects @(subscribe [:self/projects true])
            member-projects (->> all-projects (filter :member?))
            available-projects (->> all-projects (remove :member?))]
        [:div.ui.stackable.grid
         [:div.row
          {:style {:padding-bottom "0"}}
          [:div.sixteen.wide.column
           [CreateProject]]]
         [:div.row
          [:div.nine.wide.column.user-projects
           {:style {:margin-top "-1em"}}
           [ProjectsListSegment "Your Projects" member-projects true]
           [ProjectsListSegment "Available Projects" available-projects false]]
          [:div.seven.wide.column.public-projects
           {:style {:margin-top "-1em"}}
           [PublicProjectsList]]]]))))