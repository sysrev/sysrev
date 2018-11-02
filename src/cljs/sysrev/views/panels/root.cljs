(ns sysrev.views.panels.root
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.nav :as nav]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.select-project :as select]
            [sysrev.views.panels.create-project :as create]
            [sysrev.shared.text :as text]
            [sysrev.util :as util])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:root])

(def-data :global-stats
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :global-stats)))
  :uri (fn [] "/api/global-stats")
  :prereqs (fn [] [[:identity]])
  :process (fn [{:keys [db]} _ {:keys [stats]}]
             (when stats
               {:db (assoc-in db [:data :global-stats] stats)})))

(reg-sub :global-stats (fn [db _] (get-in db [:data :global-stats])))

(defn PublicProjectsList []
  [:div.ui.segments.projects-list
   {:style {:margin-top "0"}}
   [:div.ui.segment.projects-list-header
    [:h4.ui.header
     "Example Projects"]]
   (doall
    (for [project-id [100 269 2026 844 3144]]
      (when-let [project (get @(subscribe [:public-projects]) project-id)]
        ^{:key [:public-project project-id]}
        [select/ProjectListItem
         {:project-id project-id
          :name (:name project)
          :member? true}])))])

(defn IntroSegment []
  [:div.ui.segments>div.ui.segment.welcome-msg
   [:div.description.wrapper.open-sans
    [:p [:span.site-name "sysrev"]
     (first text/site-intro-text)]
    [:p "Create a project to get started or explore the public example projects below."]]])

(defn GlobalStatsReport []
  [:div.ui.segments>div.ui.segment.global-stats
   (with-loader [[:global-stats]] {:dimmer :fixed}
     (let [{:keys [labeled-articles label-entries real-users real-projects]}
           @(subscribe [:global-stats])]
       [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
        [:div.column
         [:p [:span.bold (str real-projects)] " user projects"]]
        [:div.column
         [:p [:span.bold (str labeled-articles)] " total articles labeled"]]
        [:div.column
         [:p [:span.bold (str label-entries)] " user labels on articles"]]]))])

(defn RootFullPanelPublic []
  (with-loader [[:identity]
                [:public-projects]
                [:global-stats]] {}
    [:div.landing-page
     [:div.ui.stackable.grid
      (when-not (util/mobile?)
        [:div.row {:style {:padding-bottom "0"}}
         [:div.sixteen.wide.column
          [GlobalStatsReport]]])
      [:div.row {:style {:padding-bottom "0"}}
       [:div.sixteen.wide.column
        [IntroSegment]]]
      [:div.row
       [:div.nine.wide.column
        [PublicProjectsList]]
       [:div.seven.wide.column
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
           [:div.nine.wide.column.user-projects
            {:style {:margin-top "-1em"}}
            [select/ProjectsListSegment "Your Projects" member-projects true]
            [select/ProjectsListSegment "Available Projects" available-projects false]]
           [:div.seven.wide.column.public-projects
            [PublicProjectsList]]]]]))))

(defmethod panel-content [:root] []
  (fn [child]
    [RootFullPanelUser]))

(defmethod logged-out-content [:root] []
  [RootFullPanelPublic])
