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

(reg-sub :landing-page?
         :<- [:active-panel]
         :<- [:self/logged-in?]
         (fn [[active-panel logged-in?]]
           (and (= active-panel [:root])
                (not logged-in?))))

(def-data :global-stats
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :global-stats)))
  :uri (fn [] "/api/global-stats")
  :process (fn [{:keys [db]} _ {:keys [stats]}]
             (when stats
               {:db (assoc-in db [:data :global-stats] stats)})))

(reg-sub :global-stats (fn [db _] (get-in db [:data :global-stats])))

(defn IntroSegment []
  [:div.welcome-msg
   [:div.description.wrapper.open-sans
    [:p [:span.site-name "sysrev"]
     (first text/site-intro-text)]
    [:p "Create a project to get started, or explore the featured public projects below."]]])

(defn GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
     (let [{:keys [labeled-articles label-entries real-users real-projects]}
           @(subscribe [:global-stats])]
       [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
        [:div.column ;;.left.aligned
         [:p [:span.bold (str real-projects)] " user projects"]]
        [:div.column
         [:p [:span.bold (str labeled-articles)] " articles labeled"]]
        [:div.column ;;.right.aligned
         [:p [:span.bold (str label-entries)] " user labels on articles"]]]))])

(defn RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [:div.section.section1
      [:div.ui.container
       [:div.ui.stackable.grid
        [:div.middle.aligned.row
         [:div.sixteen.wide.column
          [GlobalStatsReport]]]]]]
     [:div.section.intro-section
      [:div.ui.container.segments>div.ui.stackable.grid.segment.divided
       [:div.middle.aligned.row
        [:div.nine.wide.column {:style {:padding-left "2em" :padding-right "2em"}}
         [IntroSegment]]
        [:div.seven.wide.column {:style {:padding-left "0" :padding-right "0"}}
         [LoginRegisterPanel]]]]]
     [:div.section.section3
      [:div.ui.container
       [:div.ui.middle.aligned.stackable.grid
        [:div.nine.wide.column
         [plist/FeaturedProjectsList]]
        [:div.seven.wide.column
         [:a {:href "https://blog.sysrev.com/getting-started/"
              :target "_blank"}
          [:img {:src "/getting_started_01.png"
                 :title "Sysrev Blog - Getting Started"
                 :alt "Getting Started"
                 :style {:border "1.5px solid rgba(128,128,128,0.3)"
                         :box-shadow "none"
                         :max-width "98%"
                         :height "auto"
                         :border-radius "4px"}}]]]]]]
     [:div.section.section4
      [:div.ui.container
       [Pricing]]]]))

(defn RootFullPanelUser []
  [:div.landing-page
   [plist/UserProjectListFull]])

(defmethod panel-content [:root] []
  (fn [child]
    [RootFullPanelUser]))

(defmethod logged-out-content [:root] []
  [RootFullPanelPublic])
