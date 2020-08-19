(ns sysrev.views.panels.root
  (:require [cljs-time.core :as time]
            [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.pricing :refer [Pricing]]
            [sysrev.views.project-list :as plist]
            [sysrev.shared.text :as text]
            [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:root])

(reg-sub :landing-page?
         :<- [:active-panel]
         :<- [:self/logged-in?]
         (fn [[active-panel logged-in?]]
           (and (= active-panel panel) (not logged-in?))))

(def-data :global-stats
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :global-stats)))
  :uri (constantly "/api/global-stats")
  :process (fn [{:keys [db]} _ {:keys [stats]}]
             (when stats
               {:db (assoc-in db [:data :global-stats] stats)})))

(reg-sub :global-stats #(get-in % [:data :global-stats]))

(defn IntroSegment []
  [:div.welcome-msg
   [:div.description.wrapper.open-sans
    [:p [:span.site-name "sysrev"]
     (first text/site-intro-text)]
    [:p "Easily review "] [:div {:style {:font-size "1.25rem"}} [:ul [:li "PDFs"]
                                                                 [:li {:style {:padding-top "0.25rem"}} "RIS citations"]
                                                                 [:li {:style {:padding-top "0.25rem"}} "EndNote" [:sup "®"] " exports"]
                                                                 [:li {:style {:padding-top "0.25rem"}} "PubMed" [:sup "®"] " exports"]
                                                                 [:li {:style {:padding-top "0.25rem"}} "Custom documents"]]]
    [:p "Sign up for free to get started."]
    (when (< (time/now) (time/date-time 2020 9 1))
      [:h3 {:style {:margin-top "0px" :margin-bottom "10px"}}
       "Sign up for Team Pro before August 31" [:sup "th"]
       " 2020 and " [:a {:href "/promotion"} " apply here"] " to be eligible for a  $500 project award."])
    [:p "Learn more about different account types at " [:a {:href "/pricing"} "pricing"] " or the "
     [:a {:href "https://blog.sysrev.com/account-types/"} "account-types"] " blog."]]])

(defn GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
     (let [{:keys [labeled-articles label-entries real-projects]}
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
         [:div.ui.segment
          [:a.black-text {:href "https://blog.sysrev.com/intro-managed-reviews/"}
           [:h4.ui.header.blue-text "Managed Review & Data Extraction "]
           [:p "Hire Sysrev to manage all of your data extraction needs."]
           [:ol
            [:li "Collect documents"]
            [:li "Extract data"]
            [:li "Manage & recruit expert reviewers"]
            [:li "Automate review tasks"]
            [:li "Analyse resulting data"]]
           [:p "Click here to get a quote and learn more."]]]
         [:a {:href "https://blog.sysrev.com/getting-started/"
              :target "_blank"}
          [:img {:src "/getting_started_01.png"
                 :title "Sysrev Blog - Getting Started"
                 :alt "Getting Started"
                 :style {:border "1.5px solid rgba(128,128,128,0.3)"
                         :box-shadow "none"
                         :max-width "98%"
                         :height "auto"
                         :border-radius "4px"}}]]

        ]]]]
     [:div.section.section4
      [:div.ui.container
       [Pricing]]]]))

(defn RootFullPanelUser []
  [:div.landing-page [plist/UserProjectListFull]])

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelUser]))

(defmethod logged-out-content panel []
  [RootFullPanelPublic])
