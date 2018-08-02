(ns sysrev.views.panels.root
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.nav :as nav]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.select-project :as select]
            [sysrev.views.panels.create-project :as create]
            [sysrev.shared.util :as util])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:root])

(defn PublicProjectView [project-id]
  (when-let [project (get @(subscribe [:public-projects]) project-id)]
    (let [{:keys [name]} project]
      [:a.ui.grid.segment.project-list-project
       {:href (str "/p/" project-id)}
       [:div.row
        [:div.sixteen.wide.column
         [:h4.ui.header.blue-text
          [:i.grey.list.alternate.outline.icon]
          [:div.content
           [:span.project-title name]]]]]])))

(defn PublicProjectsList []
  [:div.ui.segments.projects-list
   {:style {:margin-top "0"}}
   [:div.ui.segment.projects-list-header
    [:h5.ui.header
     "Example Projects"]]
   (doall
    (for [project-id [100 269 2026 2283]]
      ^{:key [:public-project project-id]}
      [PublicProjectView project-id]))])

(defn RootFullPanelPublic []
  (with-loader [[:identity]
                [:public-projects]] {}
    [:div.landing-page
     [:div.ui.stackable.grid
      [:div.row
       [:div.ten.wide.column
        [PublicProjectsList]]
       [:div.six.wide.column
        [:div
         [LoginRegisterPanel]]]]]]))

(defn RootFullPanelUser []
  (select/ensure-state)
  (with-loader [[:identity]
                [:public-projects]] {}
    (when @(subscribe [:self/logged-in?])
      (let [all-projects @(subscribe [:self/projects true])
            member-projects (->> all-projects (filter :member?))
            available-projects (->> all-projects (remove :member?))]
        [:div.landing-page
         [:div.ui.stackable.grid
          [:div.row
           {:style {:padding-bottom "0"}}
           [:div.sixteen.wide.column
            [create/CreateProject select/state]]]
          [:div.row
           [:div.nine.wide.column
            {:style {:margin-top "-1em"}}
            [select/ProjectsListSegment "Your Projects" member-projects true]
            [select/ProjectsListSegment "Available Projects" available-projects false]]
           [:div.seven.wide.column
            [PublicProjectsList]]]]]))))

(defmethod panel-content [:root] []
  (fn [child]
    [RootFullPanelUser]))

(defmethod logged-out-content [:root] []
  [RootFullPanelPublic])
