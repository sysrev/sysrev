(ns sysrev.views.project-list
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [css]])
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
    [:div.ui.middle.aligned.stackable.two.column.grid.segment.project-list-project.non-member
     [:div.column {:class (css [(not (util/mobile?)) "twelve wide"])}
      [:a {:href (project-uri project-id "")}
       [:h4.ui.header.blue-text
        [:i.grey.list.alternate.outline.icon]
        [:div.content
         [:span.project-title name]]]]]
     [:div.right.aligned.column {:class (css [(not (util/mobile?)) "four wide"])}
      [:div.ui.tiny.button
       {:class (css [(util/mobile?) "fluid"]
                    [(not (util/mobile?)) "blue"])
        :on-click #(dispatch [:action [:join-project project-id]])}
       "Join"]]]))

(defn ProjectsListSegment [title projects member? & {:keys [id]}]
  (with-loader [[:identity]] {}
    (when (or (not-empty projects) (true? member?))
      [:div.ui.segments.projects-list
       {:class (if member? "member" "non-member")
        :id (if (nil? id)
              (if member?
                "your-projects" "available-projects")
              id)}
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

(defn PublicProjectsLanding []
  (let [all-public @(subscribe [:public-projects])]
    [:div.projects-list-landing
     [:div.ui.two.column.stackable.grid
      (doall
       (for [ids-row (->> [100 269 2026 844 3144]
                          (filter #(contains? all-public %))
                          (partition-all 2))]
         ^{:key [:project-row (first ids-row)]}
         [:div.middle.aligned.row
          (for [project-id ids-row]
            (when-let [project (get all-public project-id)]
              ^{:key [:public-project project-id]}
              [:div.column #_ {:style {:padding "0"}}
               [ProjectListItem
                {:project-id project-id
                 :name (:name project)
                 :member? true}]]))]))]]))

(defn UserProjectListFull []
  (with-loader [[:identity] [:public-projects]] {}
    (when @(subscribe [:self/logged-in?])
      (let [all-projects @(subscribe [:self/projects true])
            member-projects (->> all-projects (filter :member?))
            available-projects (->> all-projects (remove :member?))]
        [:div.ui.stackable.grid
         [:div.row {:style {:padding-bottom "0"}}
          [:div.sixteen.wide.column
           [CreateProject]]]
         [:div.row
          [:div.nine.wide.column.user-projects
           [ProjectsListSegment "Your Projects" member-projects true]
           [ProjectsListSegment "Available Projects" available-projects false]]
          [:div.seven.wide.column.public-projects
           [PublicProjectsList]]]]))))
