(ns sysrev.views.panels.root
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.nav :as nav]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.pricing :refer [Pricing]]
            [sysrev.views.project-list :as plist]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.shared.text :as text]
            [sysrev.util :as util])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:root])

(def-data :global-stats
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :global-stats)))
  :uri (fn [] "/api/global-stats")
  :process (fn [{:keys [db]} _ {:keys [stats]}]
             (when stats
               {:db (assoc-in db [:data :global-stats] stats)})))

(reg-sub :global-stats (fn [db _] (get-in db [:data :global-stats])))

(defn IntroSegment []
  [:div.ui.segments>div.ui.segment.welcome-msg
   [:div.description.wrapper.open-sans
    [:p [:span.site-name "sysrev"]
     (first text/site-intro-text)]
    [:p "Create a project to get started or explore the featured public projects below."]]])

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
        [plist/PublicProjectsList]]
       [:div.seven.wide.column
        [:div.ui.segments
         [LoginRegisterPanel]]]]]
     [:div {:style {:margin-top "50em"}} [Pricing]]]))

(defn RootFullPanelUser []
  [:div.landing-page
   [plist/UserProjectListFull]])

(defmethod panel-content [:root] []
  (fn [child]
    [RootFullPanelUser]))

(defmethod logged-out-content [:root] []
  [RootFullPanelPublic])
